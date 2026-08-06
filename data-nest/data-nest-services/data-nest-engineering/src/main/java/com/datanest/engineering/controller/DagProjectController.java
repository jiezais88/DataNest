package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DagProjectCreateRequest;
import com.datanest.engineering.dto.DagProjectDTO;
import com.datanest.engineering.dto.DagProjectUpdateRequest;
import com.datanest.engineering.service.DagProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * DAG 项目管理 API
 * 前缀：/api/dev/dag-projects（被 gateway StripPrefix=1 剥掉 /api）
 */
@RestController
@RequestMapping("/dev/dag-projects")
public class DagProjectController {

    private final DagProjectService dagProjectService;

    public DagProjectController(DagProjectService dagProjectService) {
        this.dagProjectService = dagProjectService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<DagProjectDTO>> list(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        long p = page < 1 ? 1 : page;
        long ps = pageSize < 1 ? 20 : (pageSize > 200 ? 200 : pageSize);
        return Result.ok(dagProjectService.page(name, p, ps));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<DagProjectDTO> get(@PathVariable Long id) {
        return Result.ok(dagProjectService.getById(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DagProjectDTO> create(@Valid @RequestBody DagProjectCreateRequest request) {
        return Result.ok(dagProjectService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DagProjectDTO> update(@PathVariable Long id, @Valid @RequestBody DagProjectUpdateRequest request) {
        return Result.ok(dagProjectService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dagProjectService.delete(id);
        return Result.ok(null);
    }
}
