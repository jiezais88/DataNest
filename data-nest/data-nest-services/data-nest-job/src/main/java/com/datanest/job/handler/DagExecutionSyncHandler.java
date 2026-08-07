package com.datanest.job.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.datanest.common.model.Result;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.task.core.service.DagExecutionSyncService;
import com.datanest.task.core.service.DagExecutionSyncService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DAG 执行状态定时同步任务（Sprint 3 Phase 7）
 * 决策：定时回查 DS 流程实例状态由调度引擎兜底（默认每 30 秒一次，自适应可缩短）
 * 位置：data-nest-job 服务（统一管理中台所有定时任务）
 *
 * JSON 序列化：fastjson2（最新稳定版 2.0.52+）
 *
 * Sprint 3 P1-2：实现 SyncJobHistoryFetcher SPI，查 sync_job_history 给 SYNC 节点收尾
 * Sprint3-Fix4：实现 SyncJobMutexReleaser SPI（直接操作 redis，避免跨服务依赖 engineering 的 SyncNodeMutexService）
 *
 * PowerJob 迁移后由 JobRegistrar 注册 cron（默认 "0/30 * * * * ?"）触发。
 */
@Component
public class DagExecutionSyncHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionSyncHandler.class);

    /**
     * Sprint3-Fix4：与 engineering-service SyncNodeMutexService.KEY_PREFIX 保持一致
     * （直接用 StringRedisTemplate 不跨服务依赖 engineering 包的 bean）
     */
    private static final String SYNC_LOCK_KEY_PREFIX = "datanest:dag:sync-lock:";

    private static final String HANDLER_NAME = "dagExecutionSyncHandler";

    /**
     * 多实例安全：自适应触发的分布式锁。
     * 锁过期时间 = adaptive-min-interval-ms，保证同一时刻只有一个 job 实例发起自适应触发。
     */
    private static final String ADAPTIVE_TRIGGER_LOCK_KEY = "datanest:dag-sync:adaptive-trigger-lock";

    private final DagExecutionSyncService dagExecutionSyncService;
    private final EngineeringSyncJobApi syncJobApi;
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SchedulerClient schedulerClient;
    private final String dsApiBaseUrl;
    private final String dsApiToken;
    private final boolean adaptiveEnabled;
    private final long adaptiveMinIntervalMs;

    public DagExecutionSyncHandler(DagExecutionSyncService dagExecutionSyncService,
                                   EngineeringSyncJobApi syncJobApi,
                                   RestTemplate restTemplate,
                                   StringRedisTemplate redisTemplate,
                                   SchedulerClient schedulerClient,
                                   @Value("${datanest.dolphinscheduler.api-url}") String dsApiBaseUrl,
                                   @Value("${datanest.dolphinscheduler.token}") String dsApiToken,
                                   @Value("${datanest.job.dag-sync.adaptive-enabled:true}") boolean adaptiveEnabled,
                                   @Value("${datanest.job.dag-sync.adaptive-min-interval-ms:5000}") long adaptiveMinIntervalMs) {
        this.dagExecutionSyncService = dagExecutionSyncService;
        this.syncJobApi = syncJobApi;
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.schedulerClient = schedulerClient;
        this.dsApiBaseUrl = dsApiBaseUrl;
        this.dsApiToken = dsApiToken;
        this.adaptiveEnabled = adaptiveEnabled;
        this.adaptiveMinIntervalMs = Math.max(1000L, adaptiveMinIntervalMs);
    }

    /**
     * Sprint3-Fix4：按 syncJobId 释放互斥锁（普通方法，避免 lambda 字段初始化顺序问题）。
     * 直接用 StringRedisTemplate 操作 redis（key 命名与 SyncNodeMutexService 一致）。
     */
    private void releaseSyncLock(Long syncJobId) {
        if (syncJobId == null) return;
        Boolean deleted = redisTemplate.delete(SYNC_LOCK_KEY_PREFIX + syncJobId);
        if (Boolean.TRUE.equals(deleted)) {
            logger.debug("job 端 SPI 释放 sync 互斥锁: syncJobId={}", syncJobId);
        }
    }

    @Override
    public String getName() {
        return HANDLER_NAME;
    }

    @Override
    public void execute(String param) {
        long start = System.currentTimeMillis();
        try {
            SyncResult result = dagExecutionSyncService.syncRunningExecutions(
                    new DsTaskInstanceFetcher() {
                        @Override
                        public List<DsTaskInstance> listTaskInstances(Long dsProjectCode, Long dsProcessInstanceId) {
                            return fetchTaskInstances(dsProjectCode, dsProcessInstanceId);
                        }

                        @Override
                        public Integer fetchWorkflowState(Long dsProjectCode, Long dsProcessInstanceId) {
                            return DagExecutionSyncHandler.this.fetchWorkflowState(dsProjectCode, dsProcessInstanceId);
                        }
                    },
                    new SyncJobHistoryFetcher() {
                        @Override
                        public SyncHistoryResult fetchLatestHistory(Long syncJobId) {
                            return fetchLatestSyncHistory(syncJobId, null);
                        }

                        @Override
                        public SyncHistoryResult fetchLatestHistory(Long syncJobId, LocalDateTime nodeStartTime) {
                            return fetchLatestSyncHistory(syncJobId, nodeStartTime);
                        }
                    },
                    this::releaseSyncLock);
            long cost = System.currentTimeMillis() - start;
            logger.info("DAG 执行状态同步完成: synced={}, stillRunning={}, cost={}ms",
                    result.synced(), result.stillRunning(), cost);

            // 自适应缩短间隔：本轮仍有 RUNNING 执行时，立即触发下一次同步（受最小间隔限制）。
            // PowerJob 由 server 端派发新实例，不会在 worker 内递归嵌套。
            if (adaptiveEnabled && result.stillRunning()) {
                triggerAdaptive();
            }
        } catch (Exception e) {
            logger.error("DAG 执行状态同步失败", e);
            throw new IllegalStateException("DAG 同步失败: " + e.getMessage(), e);
        }
    }

    /**
     * 自适应触发：当仍有 RUNNING execution 时，主动触发一次下一次同步。
     * 使用 Redis 分布式锁保证多实例下只有一个 job 实例发起 adaptive 触发。
     * 锁过期时间 = adaptiveMinIntervalMs，即使某个实例挂掉也不会死锁。
     */
    private void triggerAdaptive() {
        try {
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(ADAPTIVE_TRIGGER_LOCK_KEY, "1", Duration.ofMillis(adaptiveMinIntervalMs));
            if (!Boolean.TRUE.equals(locked)) {
                logger.debug("DAG 同步自适应触发未获取到分布式锁，跳过");
                return;
            }
            logger.debug("DAG 同步自适应触发获取到分布式锁");

            JsonNode job = schedulerClient.findJobByHandler(resolveJobGroup(), HANDLER_NAME);
            if (job == null) {
                logger.warn("未找到自适应触发的 PowerJob 任务: handler={}", HANDLER_NAME);
                return;
            }
            Long jobId = job.path("id").asLong();
            if (jobId != null && jobId > 0) {
                schedulerClient.triggerJob(jobId, "adaptive");
                logger.info("DAG 执行状态同步自适应触发成功: jobId={}", jobId);
            }
        } catch (Exception e) {
            logger.warn("DAG 执行状态同步自适应触发失败", e);
        }
    }

    private Long resolveJobGroup() {
        // schedulerClient.ensureJobGroup 按 appName 解析 PowerJob appId（App 已预置，不做创建）
        return schedulerClient.ensureJobGroup("data-nest-job");
    }

    /**
     * Sprint 3 P1-2：SYNC 节点 history 查询
     * 微服务化 3.2：经 EngineeringSyncJobApi.latestHistory 远程查询 app-engineering
     * （notBefore 传节点 startTime，服务端只接受 end_time 不早于该值的记录），
     * 远程失败按「本轮无结果」跳过收尾，等下一轮。
     * <p>
     * 负数耗时修复：nodeStartTime 非空时只接受 end_time 不早于它的 history
     * （即"属于本次运行"的 history），取不到则返回 null 本轮跳过收尾，等下一轮。
     */
    private SyncHistoryResult fetchLatestSyncHistory(Long syncJobId, LocalDateTime nodeStartTime) {
        if (syncJobId == null) return null;
        // 契约为 ISO 字符串（Feign ConversionService 会把 LocalDateTime 按 locale 格式化）
        String notBefore = nodeStartTime == null ? null : nodeStartTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Result<SyncHistoryInfo> result = syncJobApi.latestHistory(syncJobId, notBefore);
        SyncHistoryInfo h = result == null ? null : result.data();
        if (h == null) return null;
        // RUNNING 状态不收尾（让 sync 继续跑）
        if ("RUNNING".equalsIgnoreCase(h.getStatus())) return null;
        return new SyncHistoryResult(
                h.getStatus(),
                h.getEndTime(),
                h.getErrorMessage(),
                h.getSourceRows() + " source rows, " + h.getTargetRows() + " target rows",
                h.getId());
    }

    /**
     * DS API: GET /projects/{code}/task-instances?processInstanceId={id}&pageSize=1000&pageNo=1
     * 该端点会返回 DS 计算好的 duration（如 "1s"），与 DS UI 展示一致。
     */
    private List<DsTaskInstance> fetchTaskInstances(Long dsProjectCode, Long dsProcessInstanceId) {
        String url = dsApiBaseUrl + "/projects/" + dsProjectCode
                + "/task-instances?workflowInstanceId=" + dsProcessInstanceId
                + "&pageSize=1000&pageNo=1";
        HttpHeaders headers = new HttpHeaders();
        if (dsApiToken != null && !dsApiToken.isEmpty()) {
            headers.set("token", dsApiToken);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = resp.getBody();
            if (body == null) return List.of();
            JSONObject envelope = JSON.parseObject(body);
            if (envelope == null) return List.of();
            Integer code = envelope.getInteger("code");
            if (code == null || code != 0) {
                logger.warn("DS 返回非 0 响应: code={}, body={}", code, body);
                return List.of();
            }
            JSONObject data = envelope.getJSONObject("data");
            if (data == null) return List.of();
            JSONArray rawList = data.getJSONArray("totalList");
            if (rawList == null) {
                rawList = data.getJSONArray("taskList");
            }
            if (rawList == null) return List.of();
            List<DsTaskInstance> result = new ArrayList<>(rawList.size());
            for (int i = 0; i < rawList.size(); i++) {
                JSONObject raw = rawList.getJSONObject(i);
                if (raw != null) {
                    result.add(toDsTaskInstance(raw));
                }
            }
            return result;
        } catch (Exception e) {
            logger.warn("DS 任务列表拉取失败: dsProjectCode={}, dsProcessInstanceId={}",
                    dsProjectCode, dsProcessInstanceId, e);
            return List.of();
        }
    }

    /**
     * DS API: GET /projects/{code}/workflow-instances?id={id}&pageSize=1&pageNo=1
     * 返回流程实例 duration（毫秒），用于和 DS UI 的 DAG 整体耗时保持一致。
     */
    public Long fetchWorkflowDurationMs(Long dsProjectCode, Long dsProcessInstanceId) {
        JSONObject instance = fetchWorkflowInstance(dsProjectCode, dsProcessInstanceId);
        if (instance == null) return null;
        return parseDsDuration(strOrNull(instance.get("duration")));
    }

    /**
     * DS API: 拉取流程实例状态 code（5=STOP / 6=FAILURE / 7=SUCCESS / 9=KILL 为终态）。
     * 用于 DagExecutionSyncService 在流程实例终态后把本地仍 WAITING 的节点标 SKIPPED。
     * 查询失败返回 null（按非终态处理，绝不误标）。
     */
    public Integer fetchWorkflowState(Long dsProjectCode, Long dsProcessInstanceId) {
        JSONObject instance = fetchWorkflowInstance(dsProjectCode, dsProcessInstanceId);
        if (instance == null) return null;
        return stateOrNull(instance.get("state"));
    }

    /**
     * DS API: GET /projects/{code}/workflow-instances?id={id}&pageSize=1&pageNo=1
     * 返回流程实例 JSON（totalList 第一条），失败返回 null。
     */
    private JSONObject fetchWorkflowInstance(Long dsProjectCode, Long dsProcessInstanceId) {
        String url = dsApiBaseUrl + "/projects/" + dsProjectCode
                + "/workflow-instances?id=" + dsProcessInstanceId + "&pageSize=1&pageNo=1";
        HttpHeaders headers = new HttpHeaders();
        if (dsApiToken != null && !dsApiToken.isEmpty()) {
            headers.set("token", dsApiToken);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = resp.getBody();
            if (body == null) return null;
            JSONObject envelope = JSON.parseObject(body);
            if (envelope == null) return null;
            Integer code = envelope.getInteger("code");
            if (code == null || code != 0) {
                logger.warn("DS workflow-instances 返回非 0 响应: code={}, body={}", code, body);
                return null;
            }
            JSONObject data = envelope.getJSONObject("data");
            if (data == null) return null;
            JSONArray totalList = data.getJSONArray("totalList");
            if (totalList == null || totalList.isEmpty()) return null;
            return totalList.getJSONObject(0);
        } catch (Exception e) {
            logger.warn("DS workflow-instances 拉取失败: dsProjectCode={}, dsProcessInstanceId={}",
                    dsProjectCode, dsProcessInstanceId, e);
            return null;
        }
    }

    private DsTaskInstance toDsTaskInstance(JSONObject raw) {
        return new DsTaskInstance(
                longOrNull(raw.get("id")),
                strOrNull(raw.get("name")),
                stateOrNull(raw.get("state")),
                strOrNull(raw.get("startTime")),
                strOrNull(raw.get("endTime")),
                durationOrNull(raw.get("duration")),
                strOrNull(raw.get("errorMessage"))
        );
    }

    /**
     * 解析 DS duration 字符串（如 "1s", "1m 30s", "1h 2m 3s", "1d 2h 3m 4s"）为毫秒。
     * DS 对不足 1 秒的耗时也会展示为 "1s"，因此这里直接按 DS 的展示值解析。
     */
    private Long durationOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        return parseDsDuration(o.toString());
    }

    private Long parseDsDuration(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        long total = 0;
        // 支持 "1d 2h 3m 4s" 或 "1s"，数字与单位间可有或没有空格
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\s*(\\d+)\\s*([dhms])\\s*")
                .matcher(s);
        boolean matched = false;
        while (m.find()) {
            matched = true;
            long value = Long.parseLong(m.group(1));
            String unit = m.group(2).toLowerCase();
            total += switch (unit) {
                case "d" -> value * 24 * 60 * 60 * 1000;
                case "h" -> value * 60 * 60 * 1000;
                case "m" -> value * 60 * 1000;
                case "s" -> value * 1000;
                default -> 0;
            };
        }
        return matched ? total : null;
    }

    /**
     * DS 3.4.2 的 task instance state 序列化为枚举名字符串（如 "SUBMITTED_SUCCESS"），
     * 旧版本可能返回数字 code，两种都兼容。
     * code 与 DagExecutionSyncService.mapDsState 约定一致：
     * 5=STOP / 6=FAILURE / 7=SUCCESS / 9=KILL，其余视为运行中。
     */
    private Integer stateOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        String s = o.toString();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return switch (s.toUpperCase()) {
                case "SUBMITTED_SUCCESS" -> 0;
                case "RUNNING_EXECUTION" -> 1;
                case "READY_PAUSE" -> 2;
                case "PAUSE" -> 3;
                case "READY_STOP" -> 4;
                case "STOP" -> 5;
                case "FAILURE" -> 6;
                case "SUCCESS" -> 7;
                case "NEED_FAULT_TOLERANCE" -> 8;
                case "KILL" -> 9;
                case "WAITING_THREAD" -> 10;
                case "WAITING_DEPEND" -> 11;
                case "DELAY_EXECUTION" -> 12;
                case "FORCED_SUCCESS" -> 13;
                case "SERIAL_WAIT" -> 14;
                case "DISPATCH" -> 15;
                default -> null;
            };
        }
    }

    private Long longOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String strOrNull(Object o) {
        return o == null ? null : o.toString();
    }
}
