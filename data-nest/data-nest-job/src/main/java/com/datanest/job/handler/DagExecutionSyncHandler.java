package com.datanest.job.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.service.DagExecutionSyncService;
import com.datanest.task.core.service.DagExecutionSyncService.DsTaskInstance;
import com.datanest.task.core.service.DagExecutionSyncService.SyncHistoryResult;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * DAG 执行状态定时同步任务（Sprint 3 Phase 7）
 * 决策：定时回查 DS 流程实例状态由 XXL-JOB 调度（每 5 秒一次）
 * 位置：data-nest-job 服务（统一管理中台所有定时任务）
 *
 * JSON 序列化：fastjson2（最新稳定版 2.0.52+）
 *
 * Sprint 3 P1-2：实现 SyncJobHistoryFetcher SPI，查 sync_job_history 给 SYNC 节点收尾
 *
 * 注册到 XXL-JOB admin 后，在 admin 配 cron 触发：
 *   "0/5 * * * * ?"   （每 5 秒）
 */
@Component
public class DagExecutionSyncHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionSyncHandler.class);

    private final DagExecutionSyncService dagExecutionSyncService;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final RestTemplate restTemplate;
    private final String dsApiBaseUrl;
    private final String dsApiToken;

    public DagExecutionSyncHandler(DagExecutionSyncService dagExecutionSyncService,
                                   SyncJobHistoryMapper syncJobHistoryMapper,
                                   RestTemplate restTemplate,
                                   @Value("${datanest.dolphinscheduler.api-url}") String dsApiBaseUrl,
                                   @Value("${datanest.dolphinscheduler.token}") String dsApiToken) {
        this.dagExecutionSyncService = dagExecutionSyncService;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.restTemplate = restTemplate;
        this.dsApiBaseUrl = dsApiBaseUrl;
        this.dsApiToken = dsApiToken;
    }

    @XxlJob("dagExecutionSyncHandler")
    public void sync() {
        long start = System.currentTimeMillis();
        try {
            int synced = dagExecutionSyncService.syncRunningExecutions(
                    this::fetchTaskInstances, this::fetchLatestSyncHistory);
            long cost = System.currentTimeMillis() - start;
            logger.info("DAG 执行状态同步完成: synced={}, cost={}ms", synced, cost);
            XxlJobHelper.handleSuccess("synced=" + synced + ", cost=" + cost + "ms");
        } catch (Exception e) {
            logger.error("DAG 执行状态同步失败", e);
            XxlJobHelper.handleFail("DAG 同步失败: " + e.getMessage());
        }
    }

    /**
     * Sprint 3 P1-2：SYNC 节点 history 查询
     * 反查 sync_job_history 拿最新一条，看 status（RUNNING/SUCCESS/FAILED）决定是否收尾
     */
    private SyncHistoryResult fetchLatestSyncHistory(Long syncJobId) {
        if (syncJobId == null) return null;
        SyncJobHistory h = syncJobHistoryMapper.selectList(
                        new QueryWrapper<SyncJobHistory>()
                                .eq("sync_job_id", syncJobId)
                                .orderByDesc("id")
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (h == null) return null;
        // RUNNING 状态不收尾（让 sync 继续跑）
        if ("RUNNING".equalsIgnoreCase(h.getStatus())) return null;
        return new SyncHistoryResult(
                h.getStatus(),
                h.getEndTime(),
                h.getErrorMessage(),
                h.getSourceRows() + " source rows, " + h.getTargetRows() + " target rows");
    }

    /**
     * DS API: GET /projects/{code}/workflow-instances/{id}/tasks
     * fastjson2 解析响应
     */
    private List<DsTaskInstance> fetchTaskInstances(Long dsProjectCode, Long dsProcessInstanceId) {
        String url = dsApiBaseUrl + "/projects/" + dsProjectCode
                + "/workflow-instances/" + dsProcessInstanceId + "/tasks";
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

    private DsTaskInstance toDsTaskInstance(JSONObject raw) {
        return new DsTaskInstance(
                longOrNull(raw.get("id")),
                strOrNull(raw.get("name")),
                intOrNull(raw.get("state")),
                strOrNull(raw.get("startTime")),
                strOrNull(raw.get("endTime")),
                longOrNull(raw.get("duration")),
                strOrNull(raw.get("errorMessage"))
        );
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
