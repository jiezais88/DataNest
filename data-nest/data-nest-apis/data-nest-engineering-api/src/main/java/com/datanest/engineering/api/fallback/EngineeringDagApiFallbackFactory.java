package com.datanest.engineering.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.dto.DagEdgeInfo;
import com.datanest.engineering.api.dto.DagInfo;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.engineering.api.dto.DagParamInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * EngineeringDagApi 熔断降级工厂：定义读取整体 fail-open 为空集。
 */
@Component
public class EngineeringDagApiFallbackFactory implements FallbackFactory<EngineeringDagApi> {

    private static final Logger logger = LoggerFactory.getLogger(EngineeringDagApiFallbackFactory.class);

    @Override
    public EngineeringDagApi create(Throwable cause) {
        logger.warn("EngineeringDagApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new EngineeringDagApi() {
            @Override
            public Result<DagInfo> getById(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<Map<Long, DagInfo>> batchGet(IdsRequest request) {
                return Result.ok(Map.of());
            }

            @Override
            public Result<List<DagNodeInfo>> listNodes(Long id) {
                return Result.ok(List.of());
            }

            @Override
            public Result<DagNodeInfo> getNodeByNodeId(Long id, String nodeId) {
                return Result.ok(null);
            }

            @Override
            public Result<List<DagEdgeInfo>> listEdges(Long id) {
                return Result.ok(List.of());
            }

            @Override
            public Result<List<DagParamInfo>> listParameters(Long id) {
                return Result.ok(List.of());
            }
        };
    }
}
