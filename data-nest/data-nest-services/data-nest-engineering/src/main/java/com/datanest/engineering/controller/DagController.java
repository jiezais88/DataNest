package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.audit.AuditLog;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
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

    @Operation(summary = "DAG 列表（可按项目过滤）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<List<DagPayload>> list(@Parameter(description = "项目 ID（可选，过滤所属项目）") @RequestParam(required = false) Long projectId) {
        return Result.ok(dagService.list(projectId));
    }

    @Operation(summary = "DAG 详情（含节点与边）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<DagPayload> get(@Parameter(description = "DAG ID") @PathVariable Long id) {
        return Result.ok(dagService.getDetail(id));
    }

    @Operation(summary = "创建 DAG")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.CREATE,
            resourceId = "#result.data.id", resourceName = "#payload.name")
    @PostMapping
    public Result<DagPayload> create(@RequestBody DagPayload payload) {
        return Result.ok(dagService.create(payload));
    }

    @Operation(summary = "更新 DAG")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.UPDATE,
            resourceId = "#id", resourceName = "#payload.name")
    @PutMapping("/{id}")
    public Result<DagPayload> update(@Parameter(description = "DAG ID") @PathVariable Long id, @RequestBody DagPayload payload) {
        return Result.ok(dagService.update(id, payload));
    }

    @Operation(summary = "删除 DAG")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.DELETE, resourceId = "#id")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "DAG ID") @PathVariable Long id) {
        dagService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "手动触发 DAG 执行")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @AuditLog(resourceType = AuditResourceType.DAG, opType = AuditOpType.TRIGGER, resourceId = "#id")
    @PostMapping("/{id}/trigger")
    public Result<DagExecutionDTO> trigger(@Parameter(description = "DAG ID") @PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> params) {
        return Result.ok(dagExecutionService.trigger(id, params));
    }

    @Operation(summary = "PYTHON 节点脚本测试", description = "执行脚本并返回结果，不注册元数据、不写 node_execution")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
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
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@Parameter(description = "DAG ID") @PathVariable Long id) {
        dagService.startSchedule(id);
        return Result.ok((Void) null);
    }

    @Operation(summary = "停止 DAG 定时调度")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@Parameter(description = "DAG ID") @PathVariable Long id) {
        dagService.stopSchedule(id);
        return Result.ok((Void) null);
    }

    @Operation(summary = "停止执行实例")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
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
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/executions/{executionId}/rerun-failed")
    public Result<DagExecutionDTO> rerunFailed(@Parameter(description = "DAG ID") @PathVariable Long id,
                                               @Parameter(description = "要重跑的旧执行实例 ID") @PathVariable Long executionId) {
        return Result.ok(dagExecutionService.rerunFailed(id, executionId));
    }

    @Operation(summary = "单 DAG 执行历史")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}/executions")
    public Result<List<DagExecutionDTO>> executions(@Parameter(description = "DAG ID") @PathVariable Long id) {
        return Result.ok(dagExecutionService.listByDag(id));
    }

    /**
     * SYNC 节点执行日志：按 node_execution.sync_job_history_id 读 sync_job_log。
     * 返回结构与 /dev/sync-jobs/{id}/history/{historyId}/logs 一致，前端复用同一日志 UI。
     */
    @Operation(summary = "SYNC 节点执行日志")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/node-executions/{nodeExecutionId}/logs")
    public Result<List<SyncJobLogDTO>> nodeExecutionLogs(@Parameter(description = "节点执行 ID") @PathVariable Long nodeExecutionId) {
        return Result.ok(dagExecutionService.getNodeExecutionLogs(nodeExecutionId));
    }
}
