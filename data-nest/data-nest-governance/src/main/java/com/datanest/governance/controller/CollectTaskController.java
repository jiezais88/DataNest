package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.dto.DataSourceReferenceDTO;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.CollectTaskCreateRequest;
import com.datanest.governance.dto.CollectTaskDTO;
import com.datanest.governance.dto.CollectTaskQueryRequest;
import com.datanest.governance.dto.CollectTaskUpdateRequest;
import com.datanest.governance.service.CollectTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/collect-tasks")
public class CollectTaskController {

    private final CollectTaskService collectTaskService;

    public CollectTaskController(CollectTaskService collectTaskService) {
        this.collectTaskService = collectTaskService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping
    public Result<CollectTaskDTO> create(@Valid @RequestBody CollectTaskCreateRequest request) {
        return Result.ok(collectTaskService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<CollectTaskDTO> update(@PathVariable Long id, @Valid @RequestBody CollectTaskUpdateRequest request) {
        return Result.ok(collectTaskService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        collectTaskService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<CollectTaskDTO> getById(@PathVariable Long id) {
        return Result.ok(collectTaskService.getById(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<CollectTaskDTO>> list(@RequestBody CollectTaskQueryRequest request) {
        return Result.ok(collectTaskService.list(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/{id}/execute")
    public Result<Void> execute(@PathVariable Long id) {
        collectTaskService.execute(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/datasources/{datasourceId}/references")
    public Result<List<DataSourceReferenceDTO>> getReferencesByDataSource(@PathVariable Long datasourceId) {
        return Result.ok(collectTaskService.getReferencesByDataSource(datasourceId));
    }
}
