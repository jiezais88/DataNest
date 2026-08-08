package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.CollectWriteApi;
import com.datanest.governance.api.dto.CollectChangeDetailBatchRequest;
import com.datanest.governance.api.dto.CollectDetectDeletedTablesRequest;
import com.datanest.governance.api.dto.CollectHistoryCreateRequest;
import com.datanest.governance.api.dto.CollectHistoryFinishRequest;
import com.datanest.governance.api.dto.CollectHistoryInfoDTO;
import com.datanest.governance.api.dto.CollectLogAppendRequest;
import com.datanest.governance.api.dto.CollectTaskCreateInternalRequest;
import com.datanest.governance.api.dto.CollectTaskInfoDTO;
import com.datanest.governance.api.dto.CollectTaskMarkStatusRequest;
import com.datanest.governance.api.dto.CollectUpsertColumnsRequest;
import com.datanest.governance.api.dto.CollectUpsertTableRequest;
import com.datanest.governance.api.dto.DetectDeletedResultDTO;
import com.datanest.governance.api.dto.UpsertColumnsResultDTO;
import com.datanest.governance.api.dto.UpsertTableResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * CollectWriteApi 熔断降级工厂。
 * <p>
 * 采集回写是采集主流程的关键路径，降级统一返回空（null），由调用方按 fail-fast 处理
 * （任务/历史读不到不启动采集，回写失败按采集失败收尾），不在此处掩盖。
 */
@Component
public class CollectWriteApiFallbackFactory implements FallbackFactory<CollectWriteApi> {

    private static final Logger logger = LoggerFactory.getLogger(CollectWriteApiFallbackFactory.class);

    @Override
    public CollectWriteApi create(Throwable cause) {
        logger.warn("CollectWriteApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new CollectWriteApi() {
            @Override
            public Result<CollectTaskInfoDTO> getTask(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> createTask(CollectTaskCreateInternalRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> markTaskStatus(Long id, CollectTaskMarkStatusRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> createHistory(CollectHistoryCreateRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<CollectHistoryInfoDTO> getHistory(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> finishHistory(Long id, CollectHistoryFinishRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> appendLogs(Long id, CollectLogAppendRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> batchChangeDetails(Long id, CollectChangeDetailBatchRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<UpsertTableResultDTO> upsertTable(CollectUpsertTableRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<UpsertColumnsResultDTO> upsertColumns(CollectUpsertColumnsRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<DetectDeletedResultDTO> detectDeletedTables(CollectDetectDeletedTablesRequest request) {
                return Result.ok(null);
            }
        };
    }
}
