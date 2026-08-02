package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<List<DagPayload>> list(@RequestParam(required = false) Long projectId) {
        return Result.ok(dagService.list(projectId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<DagPayload> get(@PathVariable Long id) {
        return Result.ok(dagService.getDetail(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DagPayload> create(@RequestBody DagPayload payload) {
        return Result.ok(dagService.create(payload));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DagPayload> update(@PathVariable Long id, @RequestBody DagPayload payload) {
        return Result.ok(dagService.update(id, payload));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dagService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/trigger")
    public Result<DagExecutionDTO> trigger(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> params) {
        return Result.ok(dagExecutionService.trigger(id, params));
    }

    /**
     * PYTHON 节点脚本测试：执行脚本并返回结果，不注册元数据、不写 node_execution。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/nodes/{nodeId}/python/test")
    public Result<PythonExecuteResult> testPythonNode(@PathVariable Long id,
                                                      @PathVariable String nodeId,
                                                      @RequestBody PythonTestRequest request) {
        if (request == null || !org.springframework.util.StringUtils.hasText(request.getPythonScript())) {
            return Result.fail(400, "pythonScript 不能为空");
        }
        Map<String, Object> params = dagParameterService.resolveParams(id, request.getParams());
        String script = dagParameterService.replacePlaceholders(request.getPythonScript(), params);
        PythonExecuteResult result = pythonExecutor.execute(
                script, new PythonExecutor.PythonContext(params, null), null, null);
        return Result.ok(result);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@PathVariable Long id) {
        dagService.startSchedule(id);
        return Result.ok((Void) null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@PathVariable Long id) {
        dagService.stopSchedule(id);
        return Result.ok((Void) null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/executions/{executionId}/stop")
    public Result<Void> stop(@PathVariable Long id, @PathVariable Long executionId) {
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
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/executions/{executionId}/rerun-failed")
    public Result<DagExecutionDTO> rerunFailed(@PathVariable Long id, @PathVariable Long executionId) {
        return Result.ok(dagExecutionService.rerunFailed(id, executionId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}/executions")
    public Result<List<DagExecutionDTO>> executions(@PathVariable Long id) {
        return Result.ok(dagExecutionService.listByDag(id));
    }

    /**
     * SYNC 节点执行日志：按 node_execution.sync_job_history_id 读 sync_job_log。
     * 返回结构与 /dev/sync-jobs/{id}/history/{historyId}/logs 一致，前端复用同一日志 UI。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/node-executions/{nodeExecutionId}/logs")
    public Result<List<SyncJobLogDTO>> nodeExecutionLogs(@PathVariable Long nodeExecutionId) {
        return Result.ok(dagExecutionService.getNodeExecutionLogs(nodeExecutionId));
    }
}
