package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.governance.api.dto.ObjectNameRequest;
import com.datanest.governance.api.dto.ObjectOptionDTO;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GovernanceObjectApi 熔断降级工厂。
 * <p>
 * 对象名称/下拉解析降级为空集；质量自动触发降级为空操作（下次 DAG 成功会再触发），
 * 均不阻断主流程。
 */
@Component
public class GovernanceObjectApiFallbackFactory implements FallbackFactory<GovernanceObjectApi> {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceObjectApiFallbackFactory.class);

    @Override
    public GovernanceObjectApi create(Throwable cause) {
        logger.warn("GovernanceObjectApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new GovernanceObjectApi() {
            @Override
            public Result<Map<Long, String>> names(ObjectNameRequest request) {
                return Result.ok(Map.of());
            }

            @Override
            public Result<List<ObjectOptionDTO>> options(String objectType) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Void> qualityAutoTriggerBatch(QualityAutoTriggerBatchRequest request) {
                return Result.ok(null);
            }
        };
    }
}
