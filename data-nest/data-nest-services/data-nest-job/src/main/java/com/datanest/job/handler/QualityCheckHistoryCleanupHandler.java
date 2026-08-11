package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceOpsApi;
import com.datanest.governance.api.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 质量检查历史定时清理任务（Sprint 6 补全）。
 * <p>
 * 清理超过保留天数的 quality_check_batch 及其关联 quality_check_detail，
 * 避免质量检查高频执行（定时/自动触发）导致执行历史无限膨胀。
 * <p>
 * 微服务化 4.3：质量检查表归治理域，清理逻辑（分批 500、级联明细）下沉 governance
 * （{@code POST /governance/internal/quality/cleanup}），本 handler 只负责调度触发。
 * RemoteCalls 容错：governance 不可用本轮跳过，下轮调度再来。
 */
@Component
public class QualityCheckHistoryCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckHistoryCleanupHandler.class);

    private final GovernanceOpsApi governanceOpsApi;
    private final int retainDays;
    private final int scoreHistoryRetainDays;

    public QualityCheckHistoryCleanupHandler(GovernanceOpsApi governanceOpsApi,
                                             @Value("${datanest.job.quality-check-cleanup.retain-days:30}") int retainDays,
                                             @Value("${datanest.job.quality-check-cleanup.score-history-retain-days:90}") int scoreHistoryRetainDays) {
        this.governanceOpsApi = governanceOpsApi;
        this.retainDays = Math.max(1, retainDays);
        this.scoreHistoryRetainDays = Math.max(1, scoreHistoryRetainDays);
    }

    @Override
    public String getName() {
        return "qualityCheckHistoryCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting quality check history cleanup, retainDays={}, scoreHistoryRetainDays={}",
                retainDays, scoreHistoryRetainDays);
        CleanupRequest request = new CleanupRequest();
        request.setRetainDays(retainDays);
        // Sprint 8 F3：评分快照历史随质量历史同任务清理（独立保留天数）
        request.setScoreHistoryRetainDays(scoreHistoryRetainDays);
        Integer rows = RemoteCalls.execute("governance.ops.quality-cleanup", () -> {
            Result<Integer> result = governanceOpsApi.cleanupQualityCheckHistory(request);
            return result == null ? null : result.data();
        }, null);
        if (rows == null) {
            throw new IllegalStateException("质量检查历史清理失败: governance 服务不可用，本轮跳过");
        }
        logger.info("Quality check history cleanup completed: rows={}", rows);
    }
}
