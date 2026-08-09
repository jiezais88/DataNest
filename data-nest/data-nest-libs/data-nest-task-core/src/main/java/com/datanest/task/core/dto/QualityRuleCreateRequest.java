package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 质量规则新增请求（Sprint 7 规则独立化）。
 * <p>
 * 规则 = 来源模板 + 表 + 字段 + 阈值 + 权重。CUSTOM_SQL 由用户自带 sqlExpression；
 * PYTHON 由用户自带 pythonScript（def check(df) 返回 dict，必填 resultMetric，Sprint 7 DG-10）。
 * jobId 可选：规则可独立创建（不绑定任务），任务通过 quality_job_rule 关联表引用规则。
 * 校验：tableId 必填；RANGE 必填 columnName + min/max（经 min/max 占位符合成阈值区间，存 warning/severe 阈值）；
 * UNIQUENESS 必填 columnName；COMPLETENESS 可不填字段（整表）。
 */
@Schema(description = "质量规则新增请求")
@Data
public class QualityRuleCreateRequest {

    @Schema(description = "所属质量任务 ID（可选，规则独立创建时可空；任务引用规则经关联表）", example = "1234567890123456789")
    private Long jobId;

    @Schema(description = "来源模板 ID（非 CUSTOM_SQL 必填；CUSTOM_SQL 可空，直接填自定义 SQL）", example = "1234567890123456789")
    private Long templateId;

    @Schema(description = "规则名称")
    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称不能超过 100 字符")
    private String name;

    @Schema(description = "规则类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    @NotBlank(message = "规则类型不能为空")
    @Pattern(regexp = "^(COMPLETENESS|UNIQUENESS|RANGE|CUSTOM_SQL|PYTHON)$", message = "规则类型非法")
    private String type;

    @Schema(description = "目标表 ID（metadata_table.id）", example = "1234567890123456789")
    @NotNull(message = "目标表不能为空")
    private Long tableId;

    @Schema(description = "检查字段（唯一性/值域必填；完整性可空）")
    @Size(max = 128, message = "检查字段不能超过 128 字符")
    private String columnName;

    @Schema(description = "是否按字段检查（1 按字段，0 整表）")
    private Integer checkField = 0;

    @Schema(description = "实际校验 SQL（CUSTOM_SQL 必填；模板类执行时动态生成，无需传）")
    private String sqlExpression;

    @Schema(description = "Python 脚本（def check(df) 返回 dict；PYTHON 必填）")
    private String pythonScript;

    @Schema(description = "警告阈值（执行结果 ≥ 此值 → 警告）")
    private BigDecimal warningThreshold;

    @Schema(description = "严重阈值（执行结果 ≥ 此值 → 严重）")
    private BigDecimal severeThreshold;

    @Schema(description = "值域下界（RANGE 类型必填，SQL 模板 {min} 来源）")
    private BigDecimal rangeMin;

    @Schema(description = "值域上界（RANGE 类型必填，SQL 模板 {max} 来源）")
    private BigDecimal rangeMax;

    @Schema(description = "结果指标名")
    @Size(max = 50, message = "结果指标名不能超过 50 字符")
    private String resultMetric;

    @Schema(description = "权重（评分加权，默认 1）")
    @Min(value = 1, message = "权重最小为 1")
    private Integer weight = 1;

    @Schema(description = "启用状态（1 启用，0 停用；默认 1）")
    private Integer enabled = 1;

    @AssertTrue(message = "唯一性/值域/按字段完整性检查必须填写检查字段")
    public boolean isColumnValid() {
        if ("RANGE".equals(type) || "UNIQUENESS".equals(type)) {
            return columnName != null && !columnName.isBlank();
        }
        // COMPLETENESS：checkField=1（按字段）时必填字段，整表（0）可不填
        if ("COMPLETENESS".equals(type) && checkField != null && checkField == 1) {
            return columnName != null && !columnName.isBlank();
        }
        return true;
    }

    @AssertTrue(message = "自定义 SQL 规则必须填写 SQL 表达式")
    public boolean isCustomSqlValid() {
        return !"CUSTOM_SQL".equals(type) || (sqlExpression != null && !sqlExpression.isBlank());
    }

    @AssertTrue(message = "Python 规则必须填写脚本（def check(df) 返回 dict）并指定结果指标 resultMetric")
    public boolean isPythonValid() {
        if (!"PYTHON".equals(type)) {
            return true;
        }
        return pythonScript != null && !pythonScript.isBlank()
                && resultMetric != null && !resultMetric.isBlank();
    }

    @AssertTrue(message = "值域范围检查必须填写值域边界 rangeMin/rangeMax，且 rangeMin ≤ rangeMax")
    public boolean isRangeBoundsValid() {
        if (!"RANGE".equals(type)) {
            return true;
        }
        if (rangeMin == null || rangeMax == null) {
            return false;
        }
        return rangeMin.compareTo(rangeMax) <= 0;
    }
}
