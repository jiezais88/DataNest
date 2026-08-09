package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * CUSTOM_SQL 质量规则执行预览请求（Sprint 7 DG-10 强化自定义 SQL）。
 * 真实执行展开占位符后的 SQL，返回列清单 + 样例行，供用户从多指标列中选择 resultMetric。
 */
@Schema(description = "CUSTOM_SQL 质量规则执行预览请求")
@Data
public class QualitySqlPreviewExecuteRequest {

    @Schema(description = "目标表 metadata_table.id（决定执行数据源与 {table} 展开）", example = "1234567890123456789")
    @NotNull(message = "目标表不能为空")
    private Long tableId;

    @Schema(description = "自定义 SQL（可含 {table}/{column}/{min}/{max} 占位符；仅允许 SELECT/WITH 只读查询）")
    @NotBlank(message = "SQL 表达式不能为空")
    private String sqlExpression;

    @Schema(description = "检查字段（{column} 占位符来源，可空）")
    @Size(max = 128, message = "检查字段不能超过 128 字符")
    private String columnName;

    @Schema(description = "值域下界（{min} 占位符来源，可空）")
    private BigDecimal rangeMin;

    @Schema(description = "值域上界（{max} 占位符来源，可空）")
    private BigDecimal rangeMax;
}
