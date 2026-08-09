package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.*;
import com.datanest.governance.service.MetadataPreviewService;
import com.datanest.governance.service.MetadataService;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "元数据", description = "元数据浏览 / 搜索 / 注释维护 / 数据预览")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    private final MetadataService metadataService;
    private final MetadataPreviewService metadataPreviewService;

    public MetadataController(MetadataService metadataService, MetadataPreviewService metadataPreviewService) {
        this.metadataService = metadataService;
        this.metadataPreviewService = metadataPreviewService;
    }

    @Operation(summary = "已采集数据源列表")
    @GetMapping("/datasources")
    public Result<List<MetadataDatasourceDTO>> listDatasourceIds() {
        return Result.ok(metadataService.listDatasourceIds());
    }

    @Operation(summary = "元数据搜索树")
    @GetMapping("/search-tree")
    public Result<List<MetadataTreeNodeDTO>> searchTree(@Parameter(description = "关键字（模糊匹配）") @RequestParam String keyword) {
        return Result.ok(metadataService.searchTree(keyword));
    }

    @Operation(summary = "数据源下数据库列表")
    @GetMapping("/datasources/{datasourceId}/databases")
    public Result<List<String>> listDatabases(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId) {
        return Result.ok(metadataService.listDatabases(datasourceId));
    }

    @Operation(summary = "数据库下 Schema 列表")
    @GetMapping("/datasources/{datasourceId}/databases/{databaseName}/schemas")
    public Result<List<String>> listSchemas(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId,
                                            @Parameter(description = "数据库名") @PathVariable String databaseName) {
        return Result.ok(metadataService.listSchemas(datasourceId, databaseName));
    }

    @Operation(summary = "Schema 下表列表")
    @GetMapping("/datasources/{datasourceId}/databases/{databaseName}/schemas/{schemaName}/tables")
    public Result<List<MetadataTable>> listTables(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId,
                                                  @Parameter(description = "数据库名") @PathVariable String databaseName,
                                                  @Parameter(description = "Schema 名") @PathVariable String schemaName) {
        return Result.ok(metadataService.listTables(datasourceId, databaseName, schemaName));
    }

    @Operation(summary = "数据库下表列表（无 Schema）")
    @GetMapping("/datasources/{datasourceId}/databases/{databaseName}/tables")
    public Result<List<MetadataTable>> listTablesWithoutSchema(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId,
                                                               @Parameter(description = "数据库名") @PathVariable String databaseName) {
        return Result.ok(metadataService.listTables(datasourceId, databaseName, ""));
    }

    @Operation(summary = "内置 Doris 数据库列表")
    @GetMapping("/builtin-doris/databases")
    public Result<List<String>> listBuiltinDorisDatabases() {
        return Result.ok(metadataService.listBuiltinDorisDatabases());
    }

    @Operation(summary = "内置 Doris 表列表")
    @GetMapping("/builtin-doris/databases/{databaseName}/tables")
    public Result<List<String>> listBuiltinDorisTables(@Parameter(description = "数据库名") @PathVariable String databaseName) {
        return Result.ok(metadataService.listBuiltinDorisTables(databaseName));
    }

    @Operation(summary = "表元数据详情")
    @GetMapping("/tables/{tableId}")
    public Result<MetadataTable> getTable(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(metadataService.getTable(tableId));
    }

    @Operation(summary = "表字段列表")
    @GetMapping("/tables/{tableId}/columns")
    public Result<List<MetadataColumn>> listColumns(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(metadataService.listColumns(tableId));
    }

    @Operation(summary = "更新表注释")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/tables/{tableId}/comment")
    public Result<Void> updateTableComment(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                           @Valid @RequestBody MetadataCommentRequest request) {
        metadataService.updateTableComment(tableId, request.getManualComment());
        return Result.ok(null);
    }

    @Operation(summary = "更新字段注释")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/columns/{columnId}/comment")
    public Result<Void> updateColumnComment(@Parameter(description = "字段 ID") @PathVariable Long columnId,
                                            @Valid @RequestBody MetadataCommentRequest request) {
        metadataService.updateColumnComment(columnId, request.getManualComment());
        return Result.ok(null);
    }

    @Operation(summary = "更新字段备注")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/columns/{columnId}/remark")
    public Result<Void> updateColumnRemark(@Parameter(description = "字段 ID") @PathVariable Long columnId,
                                           @Valid @RequestBody MetadataRemarkRequest request) {
        metadataService.updateColumnRemark(columnId, request.getRemark());
        return Result.ok(null);
    }

    @Operation(summary = "表数据预览")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/tables/{tableId}/preview")
    public Result<MetadataPreviewResult> previewTable(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(metadataPreviewService.preview(tableId));
    }
}
