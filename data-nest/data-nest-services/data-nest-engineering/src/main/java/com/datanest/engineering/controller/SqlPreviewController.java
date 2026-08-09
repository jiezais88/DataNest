package com.datanest.engineering.controller;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.SqlPreviewRequest;
import com.datanest.engineering.dto.SqlPreviewResponse;
import com.datanest.engineering.service.SqlPreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL preview controller (dev phase).
 * <p>
 * Route: /dev/sql-preview - protected by Sa-Token (whitelist only covers
 * /dev/internal/**; this one is meant for the frontend editor, must be logged in).
 * <p>
 * Purpose: "Run Test" button in DAG editor. Validates SQL syntax and shows
 * preview result set before saving. Does NOT trigger metadata registration
 * (differs from DagNodeCallback behavior).
 * <p>
 * Decision ADR-S3-FJ-005: keep /dev/ prefix during dev; will move to
 * /api/engineering/sql/... once stable in Sprint 4.
 */
@Tag(name = "SQL 预览", description = "DAG 编辑器「运行测试」SQL 预执行")
@RestController
@RequestMapping("/dev/sql-preview")
public class SqlPreviewController {

    private final SqlPreviewService sqlPreviewService;

    public SqlPreviewController(SqlPreviewService sqlPreviewService) {
        this.sqlPreviewService = sqlPreviewService;
    }

    @Operation(summary = "SQL 预览执行", description = "逐语句执行并返回独立结果，不注册元数据")
    @PostMapping
    public Result<SqlPreviewResponse> preview(@RequestBody SqlPreviewRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.SQL_PREVIEW_FAILED, "Request body must not be empty");
        }
        SqlPreviewResponse resp = sqlPreviewService.preview(request.getSql(), request.getDatasourceId(), request.getParams());
        return Result.ok(resp);
    }
}
