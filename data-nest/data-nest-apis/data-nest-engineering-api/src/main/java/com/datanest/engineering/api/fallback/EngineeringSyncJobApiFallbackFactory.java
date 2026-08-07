package com.datanest.engineering.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.CleanupRequest;
import com.datanest.engineering.api.dto.FinishExecutionRequest;
import com.datanest.engineering.api.dto.HistoryMarkFailedRequest;
import com.datanest.engineering.api.dto.ReapStuckRequest;
import com.datanest.engineering.api.dto.RegisterRetryRequest;
import com.datanest.engineering.api.dto.SyncHistoryCreateRequest;
import com.datanest.engineering.api.dto.SyncHistoryFinishRequest;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.engineering.api.dto.SyncJobTriggerRequest;
import com.datanest.engineering.api.dto.SyncLogAppendRequest;
import com.datanest.engineering.api.dto.SyncStatusMarkRequest;
import com.datanest.engineering.api.dto.SchedulerJobIdUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EngineeringSyncJobApi 熔断降级工厂：读操作降级为空，状态翻转按未生效（false/0）处理。
 */
@Component
public class EngineeringSyncJobApiFallbackFactory implements FallbackFactory<EngineeringSyncJobApi> {

    private static final Logger logger = LoggerFactory.getLogger(EngineeringSyncJobApiFallbackFactory.class);

    @Override
    public EngineeringSyncJobApi create(Throwable cause) {
        logger.warn("EngineeringSyncJobApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new EngineeringSyncJobApi() {
            @Override
            public Result<SyncJobInfo> getById(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<List<SyncJobInfo>> listByDatasource(Long datasourceId) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Boolean> markRunning(Long id) {
                return Result.ok(false);
            }

            @Override
            public Result<Boolean> markStatus(Long id, SyncStatusMarkRequest request) {
                return Result.ok(false);
            }

            @Override
            public Result<Void> finishExecution(Long id, FinishExecutionRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> updateSchedulerJobId(Long id, SchedulerJobIdUpdateRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> trigger(Long id, SyncJobTriggerRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> createHistory(SyncHistoryCreateRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<SyncHistoryInfo> getHistory(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> finishHistory(Long id, SyncHistoryFinishRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<SyncHistoryInfo> latestHistory(Long syncJobId, String notBefore) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> successCount(Long syncJobId) {
                return Result.ok(0L);
            }

            @Override
            public Result<Void> appendLogs(Long id, SyncLogAppendRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<List<SyncHistoryInfo>> dueRetries(Integer limit) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Void> registerRetry(Long id, RegisterRetryRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Boolean> claimRetry(Long id) {
                return Result.ok(false);
            }

            @Override
            public Result<Void> markHistoryFailed(Long id, HistoryMarkFailedRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Integer> reapStuck(ReapStuckRequest request) {
                return Result.ok(0);
            }

            @Override
            public Result<Integer> cleanupHistories(CleanupRequest request) {
                return Result.ok(0);
            }
        };
    }
}
