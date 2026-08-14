package com.datanest.dataservice.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.dataservice.api.DataServiceOpsApi;
import com.datanest.dataservice.api.dto.CleanupRequest;
import com.datanest.dataservice.api.dto.DisableApisByTableRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * DataServiceOpsApi 熔断降级工厂（Sprint 10 F1）。
 * <p>
 * 数据服务不可达时返回 null；job 侧 handler 检测到 null 抛异常标记本轮失败，
 * 下轮调度重试（对齐 AssetViewLogCleanupHandler 语义）。
 */
@Component
public class DataServiceOpsApiFallbackFactory implements FallbackFactory<DataServiceOpsApi> {

    private static final Logger logger = LoggerFactory.getLogger(DataServiceOpsApiFallbackFactory.class);

    @Override
    public DataServiceOpsApi create(Throwable cause) {
        logger.warn("DataServiceOpsApi 触发熔断降级: {}",
                cause == null ? "unknown" : cause.getMessage());
        return new DataServiceOpsApi() {
            @Override
            public Result<Integer> cleanupSqlQueryHistory(CleanupRequest request) {
                return null;
            }
            @Override
            public Result<Integer> disableApisByMetadataTableIds(DisableApisByTableRequest request) {
                return Result.ok(0);
            }
        };
    }
}
