package com.datanest.task.core.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 质量规则更新请求（Sprint 6 配置层）。
 * <p>
 * 更新语义：type 可变（随模板/手动调整），列相关字段覆盖更新，阈值/权重可改。
 * 校验对齐 {@link QualityRuleCreateRequest}。
 */
@Data
public class QualityRuleUpdateRequest {

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称不能超过 100 字符")
    private String name;

    @NotBlank(message = "规则类型不能为空")
    @Pattern(regexp = "^(COMPLETENESS|UNIQUENESS|RANGE|CUSTOM_SQL|PYTHON)$", message = "规则类型非法")
    private String type;

    /** 来源模板（模板类规则必填；CUSTOM_SQL 可不填，用用户 SQL） */
    private Long templateId;

    /** 目标表（更新时可改） */
    private Long tableId;

    @Size(max = 128, message = "检查字段不能超过 128 字符")
    private String columnName;

    private Integer checkField = 0;

    private String sqlExpression;

    /** Python 脚本（def check(df) 返回 dict；PYTHON 必填，Sprint 7 DG-10） */
    private String pythonScript;

    private BigDecimal warningThreshold;

    private BigDecimal severeThreshold;

    /** 值域下界（RANGE 类型必填，SQL 模板 {min} 来源） */
    private BigDecimal rangeMin;

    /** 值域上界（RANGE 类型必填，SQL 模板 {max} 来源） */
    private BigDecimal rangeMax;

    @Size(max = 50, message = "结果指标名不能超过 50 字符")
    private String resultMetric;

    @Min(value = 1, message = "权重最小为 1")
    private Integer weight = 1;

    private Integer enabled;

    @AssertTrue(message = "唯一性/值域/按字段完整性检查必须填写检查字段")
    public boolean isColumnValid() {
        if ("RANGE".equals(type) || "UNIQUENESS".equals(type)) {
            return columnName != null && !columnName.isBlank();
        }
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
