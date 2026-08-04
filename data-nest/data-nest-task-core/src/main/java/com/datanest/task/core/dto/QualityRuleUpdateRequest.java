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
    @Pattern(regexp = "^(COMPLETENESS|UNIQUENESS|RANGE|CUSTOM_SQL)$", message = "规则类型非法")
    private String type;

    /** 目标表（更新时可改） */
    private Long tableId;

    @Size(max = 128, message = "检查字段不能超过 128 字符")
    private String columnName;

    private Integer checkField = 0;

    private String sqlExpression;

    private BigDecimal warningThreshold;

    private BigDecimal severeThreshold;

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
}
