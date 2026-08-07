package com.datanest.job.handler;

import com.datanest.common.model.Result;
import com.datanest.common.scheduler.PowerJobWorkflowClient;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.task.core.service.DagExecutionSyncService;
import com.datanest.task.core.service.DagExecutionSyncService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * DAG 执行状态定时同步任务（Sprint 3 Phase 7）
 * 决策：定时回查 PowerJob 工作流实例状态由调度引擎兜底（默认每 30 秒一次，自适应可缩短）
 * 位置：data-nest-job 服务（统一管理中台所有定时任务）
 *
 * P3 调度引擎迁移（DolphinScheduler → PowerJob）：
 * fetcher SPI 实现由直连 DS API 换成 common 的 PowerJobWorkflowClient.fetchWfInstanceInfo，
 * DS 相关的 api-url/token 配置与解析代码一并移除（DS 侧文件由 P4 统一清理）。
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

    /** DAG 相关工作流与节点任务统一挂在 data-nest-worker（appId=2） */
    private static final String POWERJOB_WORKER_APP = "data-nest-worker";

    /**
     * 多实例安全：自适应触发的分布式锁。
     * 锁过期时间 = adaptive-min-interval-ms，保证同一时刻只有一个 job 实例发起自适应触发。
     */
    private static final String ADAPTIVE_TRIGGER_LOCK_KEY = "datanest:dag-sync:adaptive-trigger-lock";

    private final DagExecutionSyncService dagExecutionSyncService;
    private final EngineeringSyncJobApi syncJobApi;
    private final StringRedisTemplate redisTemplate;
    private final SchedulerClient schedulerClient;
    private final PowerJobWorkflowClient workflowClient;
    private final boolean adaptiveEnabled;
    private final long adaptiveMinIntervalMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DagExecutionSyncHandler(DagExecutionSyncService dagExecutionSyncService,
                                   EngineeringSyncJobApi syncJobApi,
                                   StringRedisTemplate redisTemplate,
                                   SchedulerClient schedulerClient,
                                   PowerJobWorkflowClient workflowClient,
                                   @Value("${datanest.job.dag-sync.adaptive-enabled:true}") boolean adaptiveEnabled,
                                   @Value("${datanest.job.dag-sync.adaptive-min-interval-ms:5000}") long adaptiveMinIntervalMs) {
        this.dagExecutionSyncService = dagExecutionSyncService;
        this.syncJobApi = syncJobApi;
        this.redisTemplate = redisTemplate;
        this.schedulerClient = schedulerClient;
        this.workflowClient = workflowClient;
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
                    this::fetchWfInstanceSnapshot,
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
     * PowerJob OpenAPI fetchWfInstanceInfo：拉取工作流实例并解析为节点状态快照。
     * 返回 JSON 为 WorkflowInstanceInfoDTO：status=实例整体状态（3=FAILED/4=SUCCEED/10=STOPPED 终态），
     * dag 为内嵌 PEWorkflowDAG JSON 字符串，nodes[] 每项含 nodeId/instanceId/status/startTime/finishedTime。
     * 查询/解析失败返回 null（本轮按无数据处理，绝不误标）。
     */
    private WfInstanceSnapshot fetchWfInstanceSnapshot(Long wfInstanceId) {
        try {
            JsonNode info = workflowClient.fetchWfInstanceInfo(POWERJOB_WORKER_APP, wfInstanceId);
            if (info == null || info.isNull()) return null;
            // 兼容 client 返回完整 ResultDTO 信封的情况：没有 dag 字段但有 data 时下钻一层
            if (!info.hasNonNull("dag") && info.hasNonNull("data")) {
                info = info.path("data");
            }
            Integer wfStatus = intOrNull(info.path("status"));
            String dagJson = textOrNull(info.path("dag"));
            if (dagJson == null || dagJson.isEmpty()) return null;
            JsonNode dag = objectMapper.readTree(dagJson);
            List<WfNodeStatus> nodes = new ArrayList<>();
            JsonNode nodeArray = dag.path("nodes");
            if (nodeArray.isArray()) {
                for (JsonNode node : nodeArray) {
                    nodes.add(new WfNodeStatus(
                            longOrNull(node.path("nodeId")),
                            longOrNull(node.path("instanceId")),
                            intOrNull(node.path("status")),
                            textOrNull(node.path("startTime")),
                            textOrNull(node.path("finishedTime"))
                    ));
                }
            }
            return new WfInstanceSnapshot(wfStatus, nodes);
        } catch (Exception e) {
            logger.warn("PowerJob 工作流实例拉取失败: wfInstanceId={}", wfInstanceId, e);
            return null;
        }
    }

    private Long longOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asLong();
        // PEWorkflowDAG.Node.instanceId 经 ToStringSerializer 序列化为字符串
        if (node.isString()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asInt();
        if (node.isString()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
