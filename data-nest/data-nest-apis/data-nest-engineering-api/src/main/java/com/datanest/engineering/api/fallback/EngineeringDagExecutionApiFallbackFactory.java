package com.datanest.engineering.api.fallback;

import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.dto.CleanupRequest;
import com.datanest.engineering.api.dto.DagExecutionFinalizeRequest;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.EnsureDagExecutionRequest;
import com.datanest.engineering.api.dto.NodeExecutionBatchUpdateRequest;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import com.datanest.engineering.api.dto.NodeExecutionMarkRequest;
import com.datanest.engineering.api.dto.NodeLogAppendRequest;
import com.datanest.engineering.api.dto.ReapStuckRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * EngineeringDagExecutionApi 熔断降级工厂：读操作降级为空，写操作按未生效（false/0）处理。
 */
@Component
public class EngineeringDagExecutionApiFallbackFactory implements FallbackFactory<EngineeringDagExecutionApi> {

    private static final Logger logger = LoggerFactory.getLogger(EngineeringDagExecutionApiFallbackFactory.class);

    @Override
    public EngineeringDagExecutionApi create(Throwable cause) {
        logger.warn("EngineeringDagExecutionApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new EngineeringDagExecutionApi() {
            @Override
            public Result<PageResult<DagExecutionInfo>> listRunning(Integer page, Integer pageSize) {
                return Result.ok(PageResult.of(List.of(), 0, page == null ? 1 : page, pageSize == null ? 0 : pageSize));
            }

            @Override
            public Result<DagExecutionInfo> getById(Long id) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> ensureExecution(EnsureDagExecutionRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> finalizeExecution(Long id, DagExecutionFinalizeRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<List<DagExecutionInfo>> succeededBetween(String from, String to, Integer limit) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Integer> reapStuck(ReapStuckRequest request) {
                return Result.ok(0);
            }

            @Override
            public Result<Integer> cleanup(CleanupRequest request) {
                return Result.ok(0);
            }

            @Override
            public Result<List<NodeExecutionInfo>> listNodes(Long id) {
                return Result.ok(List.of());
            }

            @Override
            public Result<List<Long>> batchUpdateNodes(NodeExecutionBatchUpdateRequest request) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Boolean> markNode(Long id, NodeExecutionMarkRequest request) {
                return Result.ok(false);
            }

            @Override
            public Result<Integer> markNodesSkipped(Long id) {
                return Result.ok(0);
            }

            @Override
            public Result<List<NodeExecutionInfo>> runningWithDag(Integer limit) {
                return Result.ok(List.of());
            }

            @Override
            public Result<List<NodeExecutionInfo>> runningBySyncJob(Long syncJobId) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Void> appendNodeLogs(Long id, NodeLogAppendRequest request) {
                return Result.ok(null);
            }
        };
    }
}
