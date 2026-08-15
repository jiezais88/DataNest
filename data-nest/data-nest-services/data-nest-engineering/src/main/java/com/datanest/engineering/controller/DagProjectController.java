package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagProjectCreateRequest;
import com.datanest.engineering.dto.DagProjectDTO;
import com.datanest.engineering.dto.DagProjectUpdateRequest;
import com.datanest.engineering.service.DagProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * DAG 项目管理 API
 * 前缀：/api/dev/dag-projects（被 gateway StripPrefix=1 剥掉 /api）
 */
@Tag(name = "DAG 项目", description = "DAG 项目 CRUD")
@RestController
@RequestMapping("/dev/dag-projects")
public class DagProjectController {

    private final DagProjectService dagProjectService;

    public DagProjectController(DagProjectService dagProjectService) {
        this.dagProjectService = dagProjectService;
    }

    @Operation(summary = "DAG 项目分页列表")
    @SaCheckLogin
    @GetMapping
    public Result<PageResult<DagProjectDTO>> list(
            @Parameter(description = "项目名称（模糊匹配）") @RequestParam(required = false) String name,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") long pageSize) {
        long p = page < 1 ? 1 : page;
        long ps = pageSize < 1 ? 20 : (pageSize > 200 ? 200 : pageSize);
        return Result.ok(dagProjectService.page(name, p, ps));
    }

    @Operation(summary = "DAG 项目详情")
    @SaCheckLogin
    @GetMapping("/{id}")
    public Result<DagProjectDTO> get(@Parameter(description = "项目 ID") @PathVariable Long id) {
        return Result.ok(dagProjectService.getById(id));
    }

    @Operation(summary = "创建 DAG 项目")
    @SaCheckPermission(PermissionCode.DAG_CREATE)
    @PostMapping
    public Result<DagProjectDTO> create(@Valid @RequestBody DagProjectCreateRequest request) {
        return Result.ok(dagProjectService.create(request));
    }

    @Operation(summary = "修改 DAG 项目")
    @SaCheckPermission(PermissionCode.DAG_UPDATE)
    @PutMapping("/{id}")
    public Result<DagProjectDTO> update(@Parameter(description = "项目 ID") @PathVariable Long id, @Valid @RequestBody DagProjectUpdateRequest request) {
        return Result.ok(dagProjectService.update(id, request));
    }

    @Operation(summary = "删除 DAG 项目")
    @SaCheckPermission(PermissionCode.DAG_DELETE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "项目 ID") @PathVariable Long id) {
        dagProjectService.delete(id);
        return Result.ok(null);
    }
}
