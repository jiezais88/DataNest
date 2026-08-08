package com.datanest.engineering.controller.internal;

import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.CleanupRequest;
import com.datanest.engineering.api.dto.DagExecutionFinalizeRequest;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.EnsureDagExecutionRequest;
import com.datanest.engineering.api.dto.NodeExecutionBatchUpdateRequest;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import com.datanest.engineering.api.dto.NodeExecutionMarkRequest;
import com.datanest.engineering.api.dto.NodeLogAppendRequest;
import com.datanest.engineering.api.dto.ReapStuckRequest;
import com.datanest.engineering.service.internal.InternalDagExecutionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 执行记录域内部接口（实现 engineering-api 的 EngineeringDagExecutionApi 契约）。
 * <p>
 * Controller 只做参数校验与转发，ensureDagExecution/finalize/乐观锁批量/收割/清理/日志续号
 * 逻辑在 {@link InternalDagExecutionService}（@Transactional 保持原子性）。
 */
@RestController
@RequestMapping("/internal")
public class InternalExecutionController {

    private final InternalDagExecutionService executionService;

    public InternalExecutionController(InternalDagExecutionService executionService) {
        this.executionService = executionService;
    }

    // ==================== 执行实例 ====================

    @GetMapping("/dag-executions/running")
    public Result<PageResult<DagExecutionInfo>> listRunning(@RequestParam(defaultValue = "1") Integer page,
                                                            @RequestParam(defaultValue = "100") Integer pageSize) {
        return Result.ok(executionService.listRunning(page, pageSize));
    }

    @GetMapping("/dag-executions/{id}")
    public Result<DagExecutionInfo> getById(@PathVariable Long id) {
        return Result.ok(executionService.getById(id));
    }

    /** P3：按 PowerJob 工作流实例补齐执行记录（worker 处理 cron 触发实例时经 Feign 调用） */
    @PostMapping("/dag/ensure-execution")
    public Result<Long> ensureExecution(@RequestBody EnsureDagExecutionRequest request) {
        return Result.ok(executionService.ensureExecutionByWfInstance(request));
    }

    @PostMapping("/dag-executions/{id}/finalize")
    public Result<Void> finalizeExecution(@PathVariable Long id, @RequestBody DagExecutionFinalizeRequest request) {
        executionService.finalizeExecution(id, request);
        return Result.ok(null);
    }

    @GetMapping("/dag-executions/succeeded-between")
    public Result<List<DagExecutionInfo>> succeededBetween(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "100") Integer limit) {
        return Result.ok(executionService.succeededBetween(from, to, limit));
    }

    @PostMapping("/dag-executions/reap-stuck")
    public Result<Integer> reapStuck(@RequestBody(required = false) ReapStuckRequest request) {
        return Result.ok(executionService.reapStuckDag(request == null ? null : request.getStuckBeforeMinutes()));
    }

    @PostMapping("/dag-executions/cleanup")
    public Result<Integer> cleanup(@RequestBody(required = false) CleanupRequest request) {
        return Result.ok(executionService.cleanupDagExecutions(request == null ? null : request.getRetainDays()));
    }

    // ==================== 节点执行 ====================

    @GetMapping("/dag-executions/{id}/nodes")
    public Result<List<NodeExecutionInfo>> listNodes(@PathVariable Long id) {
        return Result.ok(executionService.listNodes(id));
    }

    @PostMapping("/node-executions/batch-update")
    public Result<List<Long>> batchUpdateNodes(@RequestBody NodeExecutionBatchUpdateRequest request) {
        return Result.ok(executionService.batchUpdateNodes(request));
    }

    @PostMapping("/node-executions/{id}/mark")
    public Result<Boolean> markNode(@PathVariable Long id, @RequestBody NodeExecutionMarkRequest request) {
        return Result.ok(executionService.markNode(id, request));
    }

    @PostMapping("/dag-executions/{id}/nodes/mark-skipped")
    public Result<Integer> markNodesSkipped(@PathVariable Long id) {
        return Result.ok(executionService.markNodesSkipped(id));
    }

    @GetMapping("/node-executions/running-with-dag")
    public Result<List<NodeExecutionInfo>> runningWithDag(@RequestParam(defaultValue = "100") Integer limit) {
        return Result.ok(executionService.runningWithDag(limit));
    }

    @GetMapping("/node-executions/running-by-sync-job/{syncJobId}")
    public Result<List<NodeExecutionInfo>> runningBySyncJob(@PathVariable Long syncJobId) {
        return Result.ok(executionService.runningBySyncJob(syncJobId));
    }

    @PostMapping("/node-executions/{id}/logs:append")
    public Result<Void> appendNodeLogs(@PathVariable Long id, @RequestBody NodeLogAppendRequest request) {
        executionService.appendNodeLogs(id, request);
        return Result.ok(null);
    }
}
