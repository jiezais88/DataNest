package com.datanest.engineering.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.DataSourceStatusUpdateRequest;
import com.datanest.engineering.api.dto.IdsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * EngineeringDatasourceApi 熔断降级工厂：读操作降级为空，写操作按未生效处理。
 */
@Component
public class EngineeringDatasourceApiFallbackFactory implements FallbackFactory<EngineeringDatasourceApi> {

    private static final Logger logger = LoggerFactory.getLogger(EngineeringDatasourceApiFallbackFactory.class);

    @Override
    public EngineeringDatasourceApi create(Throwable cause) {
        logger.warn("EngineeringDatasourceApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new EngineeringDatasourceApi() {
            @Override
            public Result<DataSourceInfo> getById(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<Map<Long, DataSourceInfo>> batchGet(IdsRequest request) {
                return Result.ok(Map.of());
            }

            @Override
            public Result<List<DataSourceInfo>> listActive() {
                return Result.ok(List.of());
            }

            @Override
            public Result<Void> updateStatus(Long id, DataSourceStatusUpdateRequest request) {
                return Result.ok(null);
            }
        };
    }
}
