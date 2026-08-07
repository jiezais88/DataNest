package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.MetadataWriteApi;
import com.datanest.governance.api.dto.LineageRecordBatchRequest;
import com.datanest.governance.api.dto.MetadataRefreshIfExistsRequest;
import com.datanest.governance.api.dto.MetadataRegisterRequest;
import com.datanest.governance.api.dto.MetadataRemoveRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * MetadataWriteApi 熔断降级工厂。
 * <p>
 * 元数据注册降级返回 null tableId；刷新/移除降级为空操作（下次任务成功会再注册/刷新）；
 * 血缘批量写入降级返回 0 条，均不阻断主流程。
 */
@Component
public class MetadataWriteApiFallbackFactory implements FallbackFactory<MetadataWriteApi> {

    private static final Logger logger = LoggerFactory.getLogger(MetadataWriteApiFallbackFactory.class);

    @Override
    public MetadataWriteApi create(Throwable cause) {
        logger.warn("MetadataWriteApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new MetadataWriteApi() {
            @Override
            public Result<Long> register(MetadataRegisterRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> refreshIfExists(MetadataRefreshIfExistsRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> remove(MetadataRemoveRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Integer> saveLineageRecords(LineageRecordBatchRequest request) {
                return Result.ok(0);
            }
        };
    }
}
