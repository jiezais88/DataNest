package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.ReapStuckRequest;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 卡死 RUNNING 执行收割任务。
 * <p>
 * worker 崩溃后 sync_job_history / node_execution / dag_execution 可能永久停留 RUNNING。
 * 微服务化 3.2/3.3：收割逻辑整体下沉 engineering，本 handler 直接调两个 reap-stuck 端点
 * （sync 域 + dag 执行域），远程失败经 RemoteCalls 降级按 0 处理，下轮再来。
 * 收割阈值由 datanest.task.stuck-running-timeout-minutes 配置（默认 120 分钟）。
 */
@Component
public class StuckExecutionReaperHandler {

    private static final Logger logger = LoggerFactory.getLogger(StuckExecutionReaperHandler.class);

    private final EngineeringSyncJobApi syncJobApi;
    private final EngineeringDagExecutionApi dagExecutionApi;
    private final long timeoutMinutes;

    public StuckExecutionReaperHandler(EngineeringSyncJobApi syncJobApi,
                                       EngineeringDagExecutionApi dagExecutionApi,
                                       @Value("${datanest.task.stuck-running-timeout-minutes:120}") long timeoutMinutes) {
        this.syncJobApi = syncJobApi;
        this.dagExecutionApi = dagExecutionApi;
        this.timeoutMinutes = Math.max(1L, timeoutMinutes);
    }

    @XxlJob("stuckExecutionReaperHandler")
    public void reap() {
        try {
            ReapStuckRequest request = new ReapStuckRequest();
            request.setStuckBeforeMinutes((int) timeoutMinutes);
            int syncHistories = RemoteCalls.execute("engineering.sync-job.reap-stuck", () -> {
                Result<Integer> result = syncJobApi.reapStuck(request);
                return result == null || result.data() == null ? 0 : result.data();
            }, 0);
            // dag 执行域：dag_execution + node_execution 一并收割，返回总处理数
            int dagSide = RemoteCalls.execute("engineering.dag-execution.reap-stuck", () -> {
                Result<Integer> result = dagExecutionApi.reapStuck(request);
                return result == null || result.data() == null ? 0 : result.data();
            }, 0);
            logger.info("卡死 RUNNING 收割完成: syncJobHistory={}, dagExecution+nodeExecution={}, timeoutMinutes={}",
                    syncHistories, dagSide, timeoutMinutes);
            XxlJobHelper.handleSuccess("收割完成: syncJobHistory=" + syncHistories
                    + ", dagExecution+nodeExecution=" + dagSide);
        } catch (Exception e) {
            logger.error("卡死 RUNNING 收割失败", e);
            XxlJobHelper.handleFail("卡死 RUNNING 收割失败: " + e.getMessage());
        }
    }
}
