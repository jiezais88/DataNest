package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * DAG 执行历史定时清理任务。
 * 清理 N 天前的终态执行记录（SUCCESS/FAILED/TERMINATED）及其 node_execution，避免历史数据无限膨胀。
 * 微服务化 3.3：清理逻辑下沉 engineering（500/批循环在服务端做），本 handler 调 cleanup 端点，
 * 远程失败经 RemoteCalls 降级按 0 处理，下轮再来。
 */
@Component
@RefreshScope
public class DagExecutionHistoryCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionHistoryCleanupHandler.class);

    private final EngineeringDagExecutionApi dagExecutionApi;
    private final int retainDays;

    public DagExecutionHistoryCleanupHandler(EngineeringDagExecutionApi dagExecutionApi,
                                             @Value("${datanest.job.dag-history-cleanup.retain-days:30}") int retainDays) {
        this.dagExecutionApi = dagExecutionApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "dagExecutionHistoryCleanupHandler";
    }

    @Override
    public void execute(String param) {
        long start = System.currentTimeMillis();
        try {
            CleanupRequest request = new CleanupRequest();
            request.setRetainDays(retainDays);
            int deletedExecutions = RemoteCalls.execute("engineering.dag-execution.cleanup", () -> {
                Result<Integer> result = dagExecutionApi.cleanup(request);
                return result == null || result.data() == null ? 0 : result.data();
            }, 0);
            long cost = System.currentTimeMillis() - start;
            logger.info("DAG 执行历史清理完成: retainDays={}, deletedExecutions={}, cost={}ms",
                    retainDays, deletedExecutions, cost);
        } catch (Exception e) {
            logger.error("DAG 执行历史清理失败", e);
            throw new IllegalStateException("DAG 执行历史清理失败: " + e.getMessage(), e);
        }
    }
}
