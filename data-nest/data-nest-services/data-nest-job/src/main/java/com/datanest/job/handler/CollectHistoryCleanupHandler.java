package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceOpsApi;
import com.datanest.governance.api.dto.CleanupRequest;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 定时清理超过 30 天的采集任务历史、执行日志与变更明细。
 * <p>
 * 微服务化 4.3：采集历史表归治理域，清理逻辑下沉 governance
 * （{@code POST /governance/internal/collect/cleanup}），本 handler 只负责调度触发。
 * RemoteCalls 容错：governance 不可用本轮跳过，下轮调度再来。
 */
@Component
public class CollectHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(CollectHistoryCleanupHandler.class);
    private static final int RETENTION_DAYS = 30;

    private final GovernanceOpsApi governanceOpsApi;

    public CollectHistoryCleanupHandler(GovernanceOpsApi governanceOpsApi) {
        this.governanceOpsApi = governanceOpsApi;
    }

    @XxlJob("collectHistoryCleanupHandler")
    public void cleanup() {
        logger.info("Starting collect history cleanup, retentionDays={}", RETENTION_DAYS);
        CleanupRequest request = new CleanupRequest();
        request.setRetainDays(RETENTION_DAYS);
        Integer rows = RemoteCalls.execute("governance.ops.collect-cleanup", () -> {
            Result<Integer> result = governanceOpsApi.cleanupCollectHistory(request);
            return result == null ? null : result.data();
        }, null);
        if (rows == null) {
            XxlJobHelper.handleFail("采集历史清理失败: governance 服务不可用，本轮跳过");
            return;
        }
        logger.info("Collect history cleanup completed: rows={}", rows);
        XxlJobHelper.handleSuccess("清理完成: rows=" + rows);
    }
}
