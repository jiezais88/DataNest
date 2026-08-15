package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.audit.AuditLog;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.ExecutionQueueCreateRequest;
import com.datanest.engineering.dto.ExecutionQueueQueryRequest;
import com.datanest.engineering.dto.ExecutionQueueUpdateRequest;
import com.datanest.engineering.dto.ExecutionQueueVO;
import com.datanest.engineering.service.ExecutionQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 执行队列管理（Sprint 11 F3 任务资源队列，PRD §6.3 QU-1~7）
 * <p>
 * 仅超管可管理（queue:manage 权限点，系统管理类）。
 */
@Tag(name = "执行队列", description = "DAG 任务资源队列的 CRUD / 并发控制 / 优先级")
@RestController
@RequestMapping("/execution-queues")
public class ExecutionQueueController {

    private final ExecutionQueueService executionQueueService;

    public ExecutionQueueController(ExecutionQueueService executionQueueService) {
        this.executionQueueService = executionQueueService;
    }

    @Operation(summary = "执行队列列表（全量，调试用；UI 走分页接口 /page）", description = "含当前运行数/等待任务数/绑定 DAG 数")
    @SaCheckPermission(PermissionCode.QUEUE_MANAGE)
    @GetMapping
    public Result<List<ExecutionQueueVO>> list() {
        return Result.ok(executionQueueService.listQueues());
    }

    @Operation(summary = "执行队列分页查询", description = "UI 列表页专用，含运行/等待/绑定数 + 审计字段 + 用户名回填")
    @SaCheckPermission(PermissionCode.QUEUE_MANAGE)
    @PostMapping("/page")
    public Result<PageResult<ExecutionQueueVO>> page(@RequestBody ExecutionQueueQueryRequest request) {
        return Result.ok(executionQueueService.pageQueues(request));
    }

    @Operation(summary = "创建执行队列")
    @SaCheckPermission(PermissionCode.QUEUE_MANAGE)
    @AuditLog(resourceType = AuditResourceType.EXECUTION_QUEUE, opType = AuditOpType.CREATE,
            resourceId = "#result.data.id", resourceName = "#request.queueName")
    @PostMapping
    public Result<ExecutionQueueVO> create(@Valid @RequestBody ExecutionQueueCreateRequest request) {
        return Result.ok(executionQueueService.createQueue(request));
    }

    @Operation(summary = "更新执行队列", description = "系统内置队列名称不可改，并发/描述可改")
    @SaCheckPermission(PermissionCode.QUEUE_MANAGE)
    @AuditLog(resourceType = AuditResourceType.EXECUTION_QUEUE, opType = AuditOpType.UPDATE,
            resourceId = "#id", resourceName = "#request.queueName")
    @PutMapping("/{id}")
    public Result<ExecutionQueueVO> update(@Parameter(description = "队列 ID") @PathVariable Long id,
                                           @Valid @RequestBody ExecutionQueueUpdateRequest request) {
        return Result.ok(executionQueueService.updateQueue(id, request));
    }

    @Operation(summary = "删除执行队列", description = "系统内置队列不可删；有 DAG 绑定拒绝删除")
    @SaCheckPermission(PermissionCode.QUEUE_MANAGE)
    @AuditLog(resourceType = AuditResourceType.EXECUTION_QUEUE, opType = AuditOpType.DELETE,
            resourceId = "#id")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "队列 ID") @PathVariable Long id) {
        executionQueueService.deleteQueue(id);
        return Result.ok(null);
    }
}