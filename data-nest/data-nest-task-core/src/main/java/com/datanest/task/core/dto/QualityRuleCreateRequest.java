package com.datanest.task.core.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 质量规则新增请求（Sprint 6 配置层）。
 * <p>
 * 规则 = 来源模板 + 表 + 字段 + 阈值 + 权重。CUSTOM_SQL 由用户自带 sqlExpression。
 * 校验：tableId 必填；RANGE 必填 columnName + min/max（经 min/max 占位符合成阈值区间，存 warning/severe 阈值）；
 * UNIQUENESS 必填 columnName；COMPLETENESS 可不填字段（整表）。
 */
@Data
public class QualityRuleCreateRequest {

    @NotNull(message = "所属质量任务不能为空")
    private Long jobId;

    /** 来源模板（可空，自定义 SQL 也记） */
    private Long templateId;

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称不能超过 100 字符")
    private String name;

    @NotBlank(message = "规则类型不能为空")
    @Pattern(regexp = "^(COMPLETENESS|UNIQUENESS|RANGE|CUSTOM_SQL)$", message = "规则类型非法")
    private String type;

    @NotNull(message = "目标表不能为空")
    private Long tableId;

    /** 检查字段（唯一性/值域必填；完整性可空） */
    @Size(max = 128, message = "检查字段不能超过 128 字符")
    private String columnName;

    /** 是否按字段检查（完整性填字段时=1，整表=0） */
    private Integer checkField = 0;

    /** 实际校验 SQL（CUSTOM_SQL 必填；模板类执行时动态生成，无需传） */
    private String sqlExpression;

    /** 警告阈值（执行结果 ≥ 此值 → 警告） */
    private BigDecimal warningThreshold;

    /** 严重阈值（执行结果 ≥ 此值 → 严重） */
    private BigDecimal severeThreshold;

    /** 结果指标名 */
    @Size(max = 50, message = "结果指标名不能超过 50 字符")
    private String resultMetric;

    /** 权重（评分加权，默认 1） */
    @Min(value = 1, message = "权重最小为 1")
    private Integer weight = 1;

    /** 启用状态：默认 1 启用 */
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
}
