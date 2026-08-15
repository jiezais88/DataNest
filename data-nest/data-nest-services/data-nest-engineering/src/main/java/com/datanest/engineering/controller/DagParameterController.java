package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagParameterPayload;
import com.datanest.engineering.service.DagParameterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "DAG 参数", description = "DAG 参数 CRUD")
@RestController
@RequestMapping("/dev/dags/{dagId}/parameters")
public class DagParameterController {

    private final DagParameterService dagParameterService;

    public DagParameterController(DagParameterService dagParameterService) {
        this.dagParameterService = dagParameterService;
    }

    @Operation(summary = "DAG 参数列表")
    @SaCheckLogin
    @GetMapping
    public Result<List<DagParameterPayload>> list(@Parameter(description = "DAG ID") @PathVariable Long dagId) {
        return Result.ok(dagParameterService.listByDagId(dagId));
    }

    @Operation(summary = "新增 DAG 参数")
    @SaCheckPermission(PermissionCode.DAG_UPDATE)
    @PostMapping
    public Result<DagParameterPayload> create(@Parameter(description = "DAG ID") @PathVariable Long dagId, @RequestBody DagParameterPayload payload) {
        return Result.ok(dagParameterService.create(dagId, payload));
    }

    @Operation(summary = "修改 DAG 参数")
    @SaCheckPermission(PermissionCode.DAG_UPDATE)
    @PutMapping("/{id}")
    public Result<DagParameterPayload> update(@Parameter(description = "DAG ID") @PathVariable Long dagId,
                                              @Parameter(description = "参数 ID") @PathVariable Long id,
                                              @RequestBody DagParameterPayload payload) {
        return Result.ok(dagParameterService.update(dagId, id, payload));
    }

    @Operation(summary = "删除 DAG 参数")
    @SaCheckPermission(PermissionCode.DAG_UPDATE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "DAG ID") @PathVariable Long dagId,
                               @Parameter(description = "参数 ID") @PathVariable Long id) {
        dagParameterService.delete(dagId, id);
        return Result.ok(null);
    }
}
