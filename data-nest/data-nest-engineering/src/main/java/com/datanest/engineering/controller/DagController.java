package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagExecutionDTO;
import com.datanest.engineering.dto.DagPayload;
import com.datanest.engineering.service.DagExecutionService;
import com.datanest.engineering.service.DagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dev/dags")
public class DagController {

    private final DagService dagService;
    private final DagExecutionService dagExecutionService;

    public DagController(DagService dagService, DagExecutionService dagExecutionService) {
        this.dagService = dagService;
        this.dagExecutionService = dagExecutionService;
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
    public Result<DagExecutionDTO> trigger(@PathVariable Long id) {
        return Result.ok(dagExecutionService.trigger(id));
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
     * Sprint 3 P1-13（差距分析 §1.13 + HTML 原型 v-history 展开行）：重跑失败节点。
     * <p>
     * 路由：POST /api/engineering/dev/dags/{id}/executions/{executionId}/rerun-failed
     * <p>
     * 当前为 MVP 简化版：直接复用 trigger 创建一个新的执行实例，
     * DagExecutionSyncService 5s 轮询会重新跑所有节点。
     * 真正的"只重跑失败节点"留 P2：需要改写 trigger 流程为「只触发 FAILED 节点子图」+ 上游节点复用。
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
}
