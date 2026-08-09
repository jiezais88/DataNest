package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.DataSourceCreateRequest;
import com.datanest.engineering.dto.DataSourceDTO;
import com.datanest.engineering.dto.DataSourceQueryRequest;
import com.datanest.engineering.dto.DataSourceUpdateRequest;
import com.datanest.engineering.service.DataSourceService;
import com.datanest.task.core.dto.TestConnectionRequest;
import com.datanest.task.core.dto.TestConnectionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "数据源", description = "数据源 CRUD / 连接测试 / 库表浏览")
@RestController
@RequestMapping("/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Operation(summary = "数据源分页查询（超管、工程师、治理员）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<DataSourceDTO>> list(@RequestBody DataSourceQueryRequest request) {
        return Result.ok(dataSourceService.list(request));
    }

    @Operation(summary = "新增数据源（超管、工程师）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DataSourceDTO> create(@Valid @RequestBody DataSourceCreateRequest request) {
        DataSourceDTO dto = dataSourceService.create(request);
        if (dto.getMessage() != null && !dto.getMessage().isBlank()) {
            return Result.ok(dto.getMessage(), dto);
        }
        return Result.ok(dto);
    }

    @Operation(summary = "修改数据源（超管、工程师）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DataSourceDTO> update(@Parameter(description = "数据源 ID") @PathVariable Long id, @Valid @RequestBody DataSourceUpdateRequest request) {
        return Result.ok(dataSourceService.update(id, request));
    }

    @Operation(summary = "删除数据源（超管、工程师）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "数据源 ID") @PathVariable Long id) {
        dataSourceService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "测试任意连接参数（超管、工程师）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/test")
    public Result<TestConnectionResult> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        return Result.ok(dataSourceService.testConnection(request));
    }

    @Operation(summary = "测试已保存的数据源并更新状态（超管、工程师）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/test")
    public Result<TestConnectionResult> testAndUpdateStatus(@Parameter(description = "数据源 ID") @PathVariable Long id) {
        return Result.ok(dataSourceService.testAndUpdateStatus(id));
    }

    @Operation(summary = "查询单个数据源详情（超管、工程师、治理员）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<DataSourceDTO> getById(@Parameter(description = "数据源 ID") @PathVariable Long id) {
        return Result.ok(dataSourceService.getById(id));
    }

    @Operation(summary = "拉取数据源下所有库/Schema（超管、工程师、治理员）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/schemas")
    public Result<List<String>> getSchemas(@Parameter(description = "数据源 ID") @PathVariable Long id) {
        return Result.ok(dataSourceService.getSchemas(id));
    }

    @Operation(summary = "拉取数据源下所有库/Database（超管、工程师、治理员）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/databases")
    public Result<List<String>> getDatabases(@Parameter(description = "数据源 ID") @PathVariable Long id) {
        return Result.ok(dataSourceService.getDatabases(id));
    }

    @Operation(summary = "拉取指定库/Schema 下的所有表（超管、工程师、治理员）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/tables")
    public Result<List<String>> getTables(@Parameter(description = "数据源 ID") @PathVariable Long id,
                                          @Parameter(description = "数据库名") @RequestParam(required = false) String database,
                                          @Parameter(description = "Schema 名") @RequestParam(required = false) String schema) {
        return Result.ok(dataSourceService.getTables(id, database, schema));
    }
}
