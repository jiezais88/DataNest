package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.MetadataCommentRequest;
import com.datanest.governance.dto.MetadataDatasourceDTO;
import com.datanest.governance.dto.MetadataRemarkRequest;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.service.MetadataService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/datasources")
    public Result<List<MetadataDatasourceDTO>> listDatasourceIds() {
        return Result.ok(metadataService.listDatasourceIds());
    }

    @GetMapping("/datasources/{datasourceId}/databases")
    public Result<List<String>> listDatabases(@PathVariable Long datasourceId) {
        return Result.ok(metadataService.listDatabases(datasourceId));
    }

    @GetMapping("/datasources/{datasourceId}/databases/{databaseName}/schemas")
    public Result<List<String>> listSchemas(@PathVariable Long datasourceId, @PathVariable String databaseName) {
        return Result.ok(metadataService.listSchemas(datasourceId, databaseName));
    }

    @GetMapping("/datasources/{datasourceId}/databases/{databaseName}/schemas/{schemaName}/tables")
    public Result<List<MetadataTable>> listTables(@PathVariable Long datasourceId,
                                                  @PathVariable String databaseName,
                                                  @PathVariable String schemaName) {
        return Result.ok(metadataService.listTables(datasourceId, databaseName, schemaName));
    }

    @GetMapping("/datasources/{datasourceId}/databases/{databaseName}/tables")
    public Result<List<MetadataTable>> listTablesWithoutSchema(@PathVariable Long datasourceId,
                                                               @PathVariable String databaseName) {
        return Result.ok(metadataService.listTables(datasourceId, databaseName, databaseName));
    }

    @GetMapping("/tables/{tableId}")
    public Result<MetadataTable> getTable(@PathVariable Long tableId) {
        return Result.ok(metadataService.getTable(tableId));
    }

    @GetMapping("/tables/{tableId}/columns")
    public Result<List<MetadataColumn>> listColumns(@PathVariable Long tableId) {
        return Result.ok(metadataService.listColumns(tableId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/tables/{tableId}/comment")
    public Result<Void> updateTableComment(@PathVariable Long tableId, @Valid @RequestBody MetadataCommentRequest request) {
        metadataService.updateTableComment(tableId, request.getManualComment());
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/columns/{columnId}/comment")
    public Result<Void> updateColumnComment(@PathVariable Long columnId, @Valid @RequestBody MetadataCommentRequest request) {
        metadataService.updateColumnComment(columnId, request.getManualComment());
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PutMapping("/columns/{columnId}/remark")
    public Result<Void> updateColumnRemark(@PathVariable Long columnId, @Valid @RequestBody MetadataRemarkRequest request) {
        metadataService.updateColumnRemark(columnId, request.getRemark());
        return Result.ok(null);
    }
}
