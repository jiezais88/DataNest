package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceOpsApi;
import com.datanest.governance.api.dto.AutoTriggerBindingRequest;
import com.datanest.governance.api.dto.AutoTriggeredBatchQueryRequest;
import com.datanest.governance.api.dto.CleanupRequest;
import com.datanest.governance.api.dto.QualityJobBindingDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GovernanceOpsApi 熔断降级工厂。
 * <p>
 * 清理/合规扫描降级返回 0（下轮调度会再执行）；对账查询降级为空集
 * （本轮对账按「无绑定/无批次」处理，下轮窗口内再补），均不阻断主流程。
 */
@Component
public class GovernanceOpsApiFallbackFactory implements FallbackFactory<GovernanceOpsApi> {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceOpsApiFallbackFactory.class);

    @Override
    public GovernanceOpsApi create(Throwable cause) {
        logger.warn("GovernanceOpsApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new GovernanceOpsApi() {
            @Override
            public Result<Integer> cleanupCollectHistory(CleanupRequest request) {
                return Result.ok(0);
            }

            @Override
            public Result<Integer> cleanupQualityCheckHistory(CleanupRequest request) {
                return Result.ok(0);
            }

            @Override
            public Result<Integer> cleanupLineageRecord(CleanupRequest request) {
                return Result.ok(0);
            }

            @Override
            public Result<Integer> runComplianceChecks() {
                return Result.ok(0);
            }

            @Override
            public Result<List<QualityJobBindingDTO>> autoTriggerBindings(AutoTriggerBindingRequest request) {
                return Result.ok(List.of());
            }

            @Override
            public Result<List<Long>> autoTriggeredJobIdsSince(AutoTriggeredBatchQueryRequest request) {
                return Result.ok(List.of());
            }
        };
    }
}
