package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * CUSTOM_SQL 质量规则执行预览响应（Sprint 7 DG-10）。
 * 多指标预览：columns 展示 SQL 返回的全部指标列，rows 为截断样例行。
 */
@Schema(description = "CUSTOM_SQL 质量规则执行预览响应")
@Data
public class QualitySqlPreviewExecuteResponse {

    @Schema(description = "执行是否成功")
    private boolean success;

    @Schema(description = "返回列名（多指标列清单，供选择 resultMetric）")
    private List<String> columns;

    @Schema(description = "样例行（按 columns 顺序，最多 50 行）")
    private List<List<Object>> rows;

    @Schema(description = "行数是否被截断")
    private boolean truncated;

    @Schema(description = "结果描述（如返回行数）")
    private String message;

    @Schema(description = "失败原因（success=false 时有值）")
    private String error;
}
