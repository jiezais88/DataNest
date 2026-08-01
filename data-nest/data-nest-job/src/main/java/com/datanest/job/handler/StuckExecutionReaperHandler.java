package com.datanest.job.handler;

import com.datanest.task.core.service.StuckExecutionReaperService;
import com.datanest.task.core.service.StuckExecutionReaperService.ReapResult;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 卡死 RUNNING 执行收割任务。
 * <p>
 * worker 崩溃后 sync_job_history / node_execution / dag_execution 可能永久停留 RUNNING，
 * 本任务周期调用 task-core 的 StuckExecutionReaperService 把超时 RUNNING 标为 FAILED。
 * 收割阈值由 datanest.task.stuck-running-timeout-minutes 配置（默认 120 分钟）。
 */
@Component
public class StuckExecutionReaperHandler {

    private static final Logger logger = LoggerFactory.getLogger(StuckExecutionReaperHandler.class);

    private final StuckExecutionReaperService stuckExecutionReaperService;

    public StuckExecutionReaperHandler(StuckExecutionReaperService stuckExecutionReaperService) {
        this.stuckExecutionReaperService = stuckExecutionReaperService;
    }

    @XxlJob("stuckExecutionReaperHandler")
    public void reap() {
        try {
            ReapResult result = stuckExecutionReaperService.reapStuckRunning();
            logger.info("卡死 RUNNING 收割完成: syncJobHistory={}, dagExecution={}, nodeExecution={}",
                    result.syncJobHistories(), result.dagExecutions(), result.nodeExecutions());
            XxlJobHelper.handleSuccess("收割完成: syncJobHistory=" + result.syncJobHistories()
                    + ", dagExecution=" + result.dagExecutions()
                    + ", nodeExecution=" + result.nodeExecutions());
        } catch (Exception e) {
            logger.error("卡死 RUNNING 收割失败", e);
            XxlJobHelper.handleFail("卡死 RUNNING 收割失败: " + e.getMessage());
        }
    }
}
