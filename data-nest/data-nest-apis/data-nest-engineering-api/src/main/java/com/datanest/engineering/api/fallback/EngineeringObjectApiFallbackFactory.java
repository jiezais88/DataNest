package com.datanest.engineering.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringObjectApi;
import com.datanest.engineering.api.dto.ObjectNameRequest;
import com.datanest.engineering.api.dto.ObjectOptionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * EngineeringObjectApi 熔断降级工厂。
 * <p>
 * 对象名称/下拉/节点 ID 解析整体 fail-open：降级为空集，名称列退化为空、
 * 质量自动触发跳过，不阻断主流程。
 */
@Component
public class EngineeringObjectApiFallbackFactory implements FallbackFactory<EngineeringObjectApi> {

    private static final Logger logger = LoggerFactory.getLogger(EngineeringObjectApiFallbackFactory.class);

    @Override
    public EngineeringObjectApi create(Throwable cause) {
        logger.warn("EngineeringObjectApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new EngineeringObjectApi() {
            @Override
            public Result<Map<Long, String>> names(ObjectNameRequest request) {
                return Result.ok(Map.of());
            }

            @Override
            public Result<List<ObjectOptionDTO>> options(String objectType) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Map<String, Long>> resolveDagNodeIds(Long dagId, List<String> nodeIds) {
                return Result.ok(Map.of());
            }
        };
    }
}
