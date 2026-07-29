package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.*;
import com.datanest.engineering.service.DataSourceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 分页查询数据源（超管、工程师、治理员）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<DataSourceDTO>> list(@RequestBody DataSourceQueryRequest request) {
        return Result.ok(dataSourceService.list(request));
    }

    /**
     * 新增数据源（超管、工程师）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<DataSourceDTO> create(@Valid @RequestBody DataSourceCreateRequest request) {
        DataSourceDTO dto = dataSourceService.create(request);
        if (dto.getMessage() != null && !dto.getMessage().isBlank()) {
            return Result.ok(dto.getMessage(), dto);
        }
        return Result.ok(dto);
    }

    /**
     * 修改数据源（超管、工程师）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<DataSourceDTO> update(@PathVariable Long id, @Valid @RequestBody DataSourceUpdateRequest request) {
        return Result.ok(dataSourceService.update(id, request));
    }

    /**
     * 删除数据源（超管、工程师）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dataSourceService.delete(id);
        return Result.ok(null);
    }

    /**
     * 测试任意连接参数（超管、工程师）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/test")
    public Result<TestConnectionResult> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        return Result.ok(dataSourceService.testConnection(request));
    }

    /**
     * 测试已保存的数据源并更新状态（超管、工程师）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/test")
    public Result<TestConnectionResult> testAndUpdateStatus(@PathVariable Long id) {
        return Result.ok(dataSourceService.testAndUpdateStatus(id));
    }

    /**
     * 查询单个数据源详情（超管、工程师、治理员）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<DataSourceDTO> getById(@PathVariable Long id) {
        return Result.ok(dataSourceService.getById(id));
    }

    /**
     * 拉取数据源下所有库/Schema（超管、工程师、治理员）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/schemas")
    public Result<List<String>> getSchemas(@PathVariable Long id) {
        return Result.ok(dataSourceService.getSchemas(id));
    }

    /**
     * 拉取数据源下所有库/Database（超管、工程师、治理员）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/databases")
    public Result<List<String>> getDatabases(@PathVariable Long id) {
        return Result.ok(dataSourceService.getDatabases(id));
    }

    /**
     * 拉取指定库/Schema 下的所有表（超管、工程师、治理员）
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/tables")
    public Result<List<String>> getTables(@PathVariable Long id,
                                          @RequestParam(required = false) String database,
                                          @RequestParam(required = false) String schema) {
        return Result.ok(dataSourceService.getTables(id, database, schema));
    }
}
