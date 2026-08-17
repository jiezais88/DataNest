package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceOpsApi;
import com.datanest.governance.api.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 资产热度记录定时清理任务（Sprint 8 F1 补全）。
 * <p>
 * 清理超过保留天数的 asset_view_log（热度按天聚合行），热度统计只用最近 30 天，
 * 老数据无价值不清理会无限增长。清理逻辑下沉 governance
 * （{@code POST /governance/internal/assets/view-log/cleanup}），本 handler 只负责调度触发。
 * RemoteCalls 容错：governance 不可用本轮跳过，下轮调度再来。
 */
@Component
@RefreshScope
public class AssetViewLogCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(AssetViewLogCleanupHandler.class);

    private final GovernanceOpsApi governanceOpsApi;
    private final int retainDays;

    public AssetViewLogCleanupHandler(GovernanceOpsApi governanceOpsApi,
                                      @Value("${datanest.job.asset-view-log-cleanup.retain-days:90}") int retainDays) {
        this.governanceOpsApi = governanceOpsApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "assetViewLogCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting asset view log cleanup, retainDays={}", retainDays);
        CleanupRequest request = new CleanupRequest();
        request.setRetainDays(retainDays);
        Integer rows = RemoteCalls.execute("governance.ops.asset-view-log-cleanup", () -> {
            Result<Integer> result = governanceOpsApi.cleanupAssetViewLog(request);
            return result == null ? null : result.data();
        }, null);
        if (rows == null) {
            throw new IllegalStateException("资产热度记录清理失败: governance 服务不可用，本轮跳过");
        }
        logger.info("Asset view log cleanup completed: rows={}", rows);
    }
}
