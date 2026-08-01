package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 执行历史定时清理任务。
 * 清理 N 天前的终态执行记录（SUCCESS/FAILED/TERMINATED）及其 node_execution，避免历史数据无限膨胀。
 */
@Component
public class DagExecutionHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionHistoryCleanupHandler.class);

    private static final int BATCH_SIZE = 500;

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final int retainDays;

    public DagExecutionHistoryCleanupHandler(DagExecutionMapper dagExecutionMapper,
                                             NodeExecutionMapper nodeExecutionMapper,
                                             @Value("${datanest.job.dag-history-cleanup.retain-days:30}") int retainDays) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.retainDays = Math.max(1, retainDays);
    }

    @XxlJob("dagExecutionHistoryCleanupHandler")
    public void cleanup() {
        long start = System.currentTimeMillis();
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(retainDays);
        int totalExecutions = 0;
        int totalNodes = 0;
        try {
            while (true) {
                List<DagExecution> executions = dagExecutionMapper.selectTerminalsBefore(beforeTime, BATCH_SIZE);
                if (executions == null || executions.isEmpty()) {
                    break;
                }
                for (DagExecution execution : executions) {
                    int nodes = nodeExecutionMapper.delete(
                            new QueryWrapper<com.datanest.task.core.entity.NodeExecution>()
                                    .eq("execution_id", execution.getId()));
                    totalNodes += nodes;
                }
                int deleted = dagExecutionMapper.delete(
                        new QueryWrapper<DagExecution>()
                                .in("id", executions.stream().map(DagExecution::getId).toList()));
                totalExecutions += deleted;
                if (executions.size() < BATCH_SIZE) {
                    break;
                }
            }
            long cost = System.currentTimeMillis() - start;
            logger.info("DAG 执行历史清理完成: beforeTime={}, deletedExecutions={}, deletedNodes={}, cost={}ms",
                    beforeTime, totalExecutions, totalNodes, cost);
            XxlJobHelper.handleSuccess("deletedExecutions=" + totalExecutions + ", deletedNodes=" + totalNodes + ", cost=" + cost + "ms");
        } catch (Exception e) {
            logger.error("DAG 执行历史清理失败", e);
            XxlJobHelper.handleFail("DAG 执行历史清理失败: " + e.getMessage());
        }
    }
}
