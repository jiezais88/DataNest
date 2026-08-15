package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.audit.AuditLog;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagExecutionDTO;
import com.datanest.engineering.dto.DagPayload;
import com.datanest.engineering.dto.PythonTestRequest;
import com.datanest.engineering.dto.SyncJobLogDTO;
import com.datanest.engineering.service.DagExecutionService;
import com.datanest.engineering.service.DagParameterService;
import com.datanest.engineering.service.DagService;
import com.datanest.task.core.dto.PythonExecuteResult;
import com.datanest.task.core.service.PythonExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "DAG 任务", description = "DAG 创建/编辑/触发/调度启停/执行管理")
@RestController
@RequestMapping("/dev/dags")
public class DagController {

    private final DagService dagService;
    private final DagExecutionService dagExecutionService;
    private final PythonExecutor pythonExecutor;
    private final DagParameterService dagParameterService;

    public DagController(DagService dagService, DagExecutionService dagExecutionService,
                         PythonExecutor pythonExecutor, DagParameterService dagParameterService) {
        this.dagService = dagService;
        this.dagExecutionService = dagExecutionService;
        this.pythonExecutor = pythonExecutor;
        this.dagParameterService = dagParameterService;
    }

    @Operation(summary = "DAG 列表（可按项目/执行队列过滤）", description = "queueName 用于执行队列详情抽屉（Sprint 11 F3）")
    @SaCheckLogin
    @GetMapping
    public Result<List<DagPayload>> list(@Parameter(description = "项目 ID（可选，过滤所属项目）") @RequestParam(required = false) Long projectId,
                                         @Parameter(description = "执行队列名（可选，过滤绑定队列）") @RequestParam(required = false) String queueName) {
        return Result.ok(dagService.list(projectId, queueName));
    }

    /** Sprint 11 F3：队列详情抽屉的 DAG 分页（按队列精确 + 多条件筛选 + 项目名/7 天执行次数回填） */
    @Operation(summary = "队列绑定 DAG 分页", description = "按 queueName 过滤，支持 DAG 名/项目名关键字、状态、优先级、触发方式筛选，返回含 projectName/executionCount7d 的分页")
    @SaCheckLogin
    @GetMapping("/page-by-queue")
    public Result<PageResult<DagPayload>> pageByQueue(
            @Parameter(description = "执行队列名（必填）") @RequestParam String queueName,
            @Parameter(description = "DAG 名/项目名关键字（可选）") @RequestParam(required = false) String keyword,
            @Parameter(description = "DAG 状态（ENABLED/DISABLED，可选）") @RequestParam(required = false) String status,
            @Parameter(description = "优先级（1/2/3，可选）") @RequestParam(required = false) Integer priority,
            @Parameter(description = "触发方式（MANUAL/CRON，可选）") @RequestParam(required = false) String triggerType,
            @Parameter(description = "页码（从 1 开始）") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(dagService.pageByQueue(queueName, keyword, status, priority, triggerType, page, pageSize));
    }

    @Operation(summary = "DAG 详情（含节点与边）")
    @SaCheckLogin
    @GetMapping("/{id}")
    public Result<DagPayload> get(@Parameter(description = "DAG ID") @PathVariable Long id) {
        return Result.ok(dagService.getDetail(id));
    }

    @Operation(summary = "创建 DAG")
    @SaCheckPermission(PermissionCode.DAG_CREATE)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.CREATE,
            resourceId = "#result.data.id", resourceName = "#payload.name")
    @PostMapping
    public Result<DagPayload> create(@RequestBody DagPayload payload) {
        return Result.ok(dagService.create(payload));
    }

    @Operation(summary = "更新 DAG")
    @SaCheckPermission(PermissionCode.DAG_UPDATE)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.UPDATE,
            resourceId = "#id", resourceName = "#payload.name")
    @PutMapping("/{id}")
    public Result<DagPayload> update(@Parameter(description = "DAG ID") @PathVariable Long id, @RequestBody DagPayload payload) {
        return Result.ok(dagService.update(id, payload));
    }

    @Operation(summary = "删除 DAG")
    @SaCheckPermission(PermissionCode.DAG_DELETE)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.DELETE, resourceId = "#id")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "DAG ID") @PathVariable Long id) {
        dagService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "手动触发 DAG 执行")
    @SaCheckPermission(PermissionCode.DAG_EXECUTE)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.TRIGGER, resourceId = "#id")
    @PostMapping("/{id}/trigger")
    public Result<DagExecutionDTO> trigger(@Parameter(description = "DAG ID") @PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> params) {
        return Result.ok(dagExecutionService.trigger(id, params));
    }

    @Operation(summary = "PYTHON 节点脚本测试", description = "执行脚本并返回结果，不注册元数据、不写 node_execution")
    @SaCheckPermission(PermissionCode.DAG_EXECUTE)
    @PostMapping("/{id}/nodes/{nodeId}/python/test")
    public Result<PythonExecuteResult> testPythonNode(@Parameter(description = "DAG ID") @PathVariable Long id,
                                                      @Parameter(description = "节点 ID") @PathVariable String nodeId,
                                                      @RequestBody PythonTestRequest request) {
        if (request == null || !org.springframework.util.StringUtils.hasText(request.getPythonScript())) {
            return Result.fail(400, "pythonScript 不能为空");
        }
        Map<String, Object> params = dagParameterService.resolveParams(id, request.getParams());
        String script = dagParameterService.replacePlaceholders(request.getPythonScript(), params);
        Integer timeoutSeconds = request.getTimeoutSeconds();
        PythonExecuteResult result = pythonExecutor.execute(
                script, new PythonExecutor.PythonContext(params, null), timeoutSeconds, null);
        return Result.ok(result);
    }

    @Operation(summary = "开启 DAG 定时调度")
    @SaCheckPermission(PermissionCode.DAG_EXECUTE)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@Parameter(description = "DAG ID") @PathVariable Long id) {
        dagService.startSchedule(id);
        return Result.ok((Void) null);
    }

    @Operation(summary = "停止 DAG 定时调度")
    @SaCheckPermission(PermissionCode.DAG_EXECUTE)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@Parameter(description = "DAG ID") @PathVariable Long id) {
        dagService.stopSchedule(id);
        return Result.ok((Void) null);
    }

    @Operation(summary = "停止执行实例")
    @SaCheckPermission(PermissionCode.DAG_EXECUTE)
    @PostMapping("/{id}/executions/{executionId}/stop")
    public Result<Void> stop(@Parameter(description = "DAG ID") @PathVariable Long id,
                             @Parameter(description = "执行实例 ID") @PathVariable Long executionId) {
        dagExecutionService.stop(id, executionId);
        return Result.ok(null);
    }

    /**
     * Sprint 4：真正的重跑失败节点。
     * <p>
     * 路由：POST /api/engineering/dev/dags/{id}/executions/{executionId}/rerun-failed
     * <p>
     * 仅重新执行原实例中 FAILED / SKIPPED 的节点，上游 SUCCESS 节点结果复用。
     * 实现方式：创建新执行记录 → 复制 node_execution（失败/跳过重置为 WAITING，成功节点保持 SUCCESS）→
     * 通过 DolphinScheduler 的 startNodeList 仅触发需要重跑的节点。
     *
     * @param id          DAG id
     * @param executionId 要重跑的旧执行实例 id
     * @return 新执行实例 DTO（含新 executionId 在 .id 字段）
     */
    @Operation(summary = "重跑失败节点")
    @SaCheckPermission(PermissionCode.DAG_EXECUTE)
    @PostMapping("/{id}/executions/{executionId}/rerun-failed")
    public Result<DagExecutionDTO> rerunFailed(@Parameter(description = "DAG ID") @PathVariable Long id,
                                               @Parameter(description = "要重跑的旧执行实例 ID") @PathVariable Long executionId) {
        return Result.ok(dagExecutionService.rerunFailed(id, executionId));
    }

    @Operation(summary = "单 DAG 执行历史")
    @SaCheckLogin
    @GetMapping("/{id}/executions")
    public Result<List<DagExecutionDTO>> executions(@Parameter(description = "DAG ID") @PathVariable Long id) {
        return Result.ok(dagExecutionService.listByDag(id));
    }

    /**
     * SYNC 节点执行日志：按 node_execution.sync_job_history_id 读 sync_job_log。
     * 返回结构与 /dev/sync-jobs/{id}/history/{historyId}/logs 一致，前端复用同一日志 UI。
     */
    @Operation(summary = "SYNC 节点执行日志")
    @SaCheckLogin
    @GetMapping("/node-executions/{nodeExecutionId}/logs")
    public Result<List<SyncJobLogDTO>> nodeExecutionLogs(@Parameter(description = "节点执行 ID") @PathVariable Long nodeExecutionId) {
        return Result.ok(dagExecutionService.getNodeExecutionLogs(nodeExecutionId));
    }
}
