package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.*;
import com.datanest.governance.service.MetadataPreviewService;
import com.datanest.governance.service.MetadataService;
import com.datanest.governance.service.internal.InternalDatasourceService;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "元数据", description = "元数据浏览 / 搜索 / 注释维护 / 数据预览")
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    private final MetadataService metadataService;
    private final MetadataPreviewService metadataPreviewService;
    private final InternalDatasourceService internalDatasourceService;

    public MetadataController(MetadataService metadataService, MetadataPreviewService metadataPreviewService,
                              InternalDatasourceService internalDatasourceService) {
        this.metadataService = metadataService;
        this.metadataPreviewService = metadataPreviewService;
        this.internalDatasourceService = internalDatasourceService;
    }

    @Operation(summary = "已采集数据源列表")
    @SaCheckLogin
    @GetMapping("/datasources")
    public Result<List<MetadataDatasourceDTO>> listDatasourceIds() {
        return Result.ok(metadataService.listDatasourceIds());
    }

    @Operation(summary = "按数据源立即触发一次元数据采集", description = "SQL 终端「去采集」入口：回读工程域连接信息 → 创建自动采集任务并立即执行，返回 collectTaskId")
    @SaCheckPermission(PermissionCode.COLLECT_EXECUTE)
    @PostMapping("/datasources/{datasourceId}/collect-now")
    public Result<Long> collectNow(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId) {
        Long operatorId;
        try {
            operatorId = StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            operatorId = 0L;
        }
        return Result.ok(internalDatasourceService.collectNow(datasourceId, operatorId));
    }

    @Operation(summary = "元数据搜索树")
    @SaCheckLogin
    @GetMapping("/search-tree")
    public Result<List<MetadataTreeNodeDTO>> searchTree(@Parameter(description = "关键字（模糊匹配）") @RequestParam String keyword) {
        return Result.ok(metadataService.searchTree(keyword));
    }

    @Operation(summary = "权限配置树", description = "返回全部可配置的外部数据源→库→表三级结构（内置 Doris 不返回），供权限配置页全量渲染")
    @SaCheckPermission(PermissionCode.DATA_PERMISSION_MANAGE)
    @GetMapping("/permission-tree")
    public Result<List<PermissionTreeDatasourceDTO>> permissionTree() {
        return Result.ok(metadataService.getPermissionTree());
    }

    @Operation(summary = "数据源下数据库列表")
    @SaCheckLogin
    @GetMapping("/datasources/{datasourceId}/databases")
    public Result<List<String>> listDatabases(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId) {
        return Result.ok(metadataService.listDatabases(datasourceId));
    }

    @Operation(summary = "数据库下 Schema 列表")
    @SaCheckLogin
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
    @SaCheckLogin
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
    @SaCheckLogin
    @GetMapping("/builtin-doris/databases/{databaseName}/tables")
    public Result<List<String>> listBuiltinDorisTables(@Parameter(description = "数据库名") @PathVariable String databaseName) {
        return Result.ok(metadataService.listBuiltinDorisTables(databaseName));
    }

    @Operation(summary = "表元数据详情")
    @SaCheckLogin
    @GetMapping("/tables/{tableId}")
    public Result<MetadataTable> getTable(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(metadataService.getTable(tableId));
    }

    @Operation(summary = "表字段列表")
    @SaCheckLogin
    @GetMapping("/tables/{tableId}/columns")
    public Result<List<MetadataColumn>> listColumns(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(metadataService.listColumns(tableId));
    }

    @Operation(summary = "更新表注释")
    @SaCheckPermission(PermissionCode.METADATA_COMMENT)
    @PutMapping("/tables/{tableId}/comment")
    public Result<Void> updateTableComment(@Parameter(description = "表 ID") @PathVariable Long tableId,
                                           @Valid @RequestBody MetadataCommentRequest request) {
        metadataService.updateTableComment(tableId, request.getManualComment());
        return Result.ok(null);
    }

    @Operation(summary = "更新字段注释")
    @SaCheckPermission(PermissionCode.METADATA_COMMENT)
    @PutMapping("/columns/{columnId}/comment")
    public Result<Void> updateColumnComment(@Parameter(description = "字段 ID") @PathVariable Long columnId,
                                            @Valid @RequestBody MetadataCommentRequest request) {
        metadataService.updateColumnComment(columnId, request.getManualComment());
        return Result.ok(null);
    }

    @Operation(summary = "更新字段备注")
    @SaCheckPermission(PermissionCode.METADATA_COMMENT)
    @PutMapping("/columns/{columnId}/remark")
    public Result<Void> updateColumnRemark(@Parameter(description = "字段 ID") @PathVariable Long columnId,
                                           @Valid @RequestBody MetadataRemarkRequest request) {
        metadataService.updateColumnRemark(columnId, request.getRemark());
        return Result.ok(null);
    }

    @Operation(summary = "表数据预览")
    @SaCheckLogin
    @GetMapping("/tables/{tableId}/preview")
    public Result<MetadataPreviewResult> previewTable(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(metadataPreviewService.preview(tableId));
    }
}
