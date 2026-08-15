package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.service.DataPreviewService;
import com.datanest.task.core.dto.DataPreviewResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@SaCheckPermission(PermissionCode.DATASOURCE_VIEW)
@Tag(name = "数据预览", description = "数据源表数据预览")
@RestController
@RequestMapping("/datasources/{datasourceId}/preview")
public class DataPreviewController {

    private final DataPreviewService dataPreviewService;

    public DataPreviewController(DataPreviewService dataPreviewService) {
        this.dataPreviewService = dataPreviewService;
    }

    @Operation(summary = "表数据预览")
    @GetMapping
    public Result<DataPreviewResult> preview(@Parameter(description = "数据源 ID") @PathVariable Long datasourceId,
                                             @Parameter(description = "数据库名") @RequestParam(required = false) String database,
                                             @Parameter(description = "Schema 名") @RequestParam(required = false) String schema,
                                             @Parameter(description = "表名") @RequestParam String table) {
        return Result.ok(dataPreviewService.preview(datasourceId, database, schema, table));
    }
}
