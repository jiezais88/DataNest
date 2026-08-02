package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.DataPreviewResult;
import com.datanest.task.core.service.DataPreviewService;
import org.springframework.web.bind.annotation.*;

@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
@RestController
@RequestMapping("/datasources/{datasourceId}/preview")
public class DataPreviewController {

    private final DataPreviewService dataPreviewService;

    public DataPreviewController(DataPreviewService dataPreviewService) {
        this.dataPreviewService = dataPreviewService;
    }

    @GetMapping
    public Result<DataPreviewResult> preview(@PathVariable Long datasourceId,
                                             @RequestParam(required = false) String database,
                                             @RequestParam(required = false) String schema,
                                             @RequestParam String table) {
        return Result.ok(dataPreviewService.preview(datasourceId, database, schema, table));
    }
}
