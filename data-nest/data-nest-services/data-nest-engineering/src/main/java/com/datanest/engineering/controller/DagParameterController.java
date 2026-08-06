package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagParameterPayload;
import com.datanest.engineering.service.DagParameterService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DAG 参数管理
 */
@RestController
@RequestMapping("/dev/dags/{dagId}/parameters")
public class DagParameterController {

    private final DagParameterService dagParameterService;

    public DagParameterController(DagParameterService dagParameterService) {
        this.dagParameterService = dagParameterService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<List<DagParameterPayload>> list(@PathVariable Long dagId) {
        return Result.ok(dagParameterService.listByDagId(dagId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DagParameterPayload> create(@PathVariable Long dagId, @RequestBody DagParameterPayload payload) {
        return Result.ok(dagParameterService.create(dagId, payload));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DagParameterPayload> update(@PathVariable Long dagId, @PathVariable Long id,
                                              @RequestBody DagParameterPayload payload) {
        return Result.ok(dagParameterService.update(dagId, id, payload));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long dagId, @PathVariable Long id) {
        dagParameterService.delete(dagId, id);
        return Result.ok(null);
    }
}
