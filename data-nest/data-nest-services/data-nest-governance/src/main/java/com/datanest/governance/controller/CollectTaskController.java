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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "元数据采集任务", description = "采集任务 CRUD / 手动执行 / 调度启停")
@RestController
@RequestMapping("/collect-tasks")
public class CollectTaskController {

    private final CollectTaskService collectTaskService;

    public CollectTaskController(CollectTaskService collectTaskService) {
        this.collectTaskService = collectTaskService;
    }

    @Operation(summary = "创建采集任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping
    public Result<CollectTaskDTO> create(@Valid @RequestBody CollectTaskCreateRequest request) {
        return Result.ok(collectTaskService.create(request));
    }

    @Operation(summary = "编辑采集任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<CollectTaskDTO> update(@Parameter(description = "采集任务 ID") @PathVariable Long id,
                                         @Valid @RequestBody CollectTaskUpdateRequest request) {
        return Result.ok(collectTaskService.update(id, request));
    }

    @Operation(summary = "删除采集任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "采集任务 ID") @PathVariable Long id) {
        collectTaskService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "采集任务详情")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<CollectTaskDTO> getById(@Parameter(description = "采集任务 ID") @PathVariable Long id) {
        return Result.ok(collectTaskService.getById(id));
    }

    @Operation(summary = "采集任务分页列表")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<CollectTaskDTO>> list(@RequestBody CollectTaskQueryRequest request) {
        return Result.ok(collectTaskService.list(request));
    }

    @Operation(summary = "手动执行采集任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/{id}/execute")
    public Result<Void> execute(@Parameter(description = "采集任务 ID") @PathVariable Long id) {
        collectTaskService.execute(id);
        return Result.ok(null);
    }

    @Operation(summary = "开启采集调度")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@Parameter(description = "采集任务 ID") @PathVariable Long id) {
        collectTaskService.startSchedule(id);
        return Result.ok(null);
    }

    @Operation(summary = "关闭采集调度")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@Parameter(description = "采集任务 ID") @PathVariable Long id) {
        collectTaskService.stopSchedule(id);
        return Result.ok(null);
    }

    @Operation(summary = "数据源被采集任务引用情况")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/datasources/{datasourceId}/references")
    public Result<List<DataSourceReferenceDTO>> getReferencesByDataSource(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId) {
        return Result.ok(collectTaskService.getReferencesByDataSource(datasourceId));
    }
}
