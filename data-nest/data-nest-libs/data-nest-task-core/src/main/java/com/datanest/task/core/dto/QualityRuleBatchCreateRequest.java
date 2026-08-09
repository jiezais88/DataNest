package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量规则模板批量应用请求（Sprint 6 配置层，D-D3）。
 * <p>
 * 交互：选「1 个模板 + 多张表」，为每张表生成独立 {@code quality_rule} 实例。
 * 逐表可微调：每个 {@link RuleItem} 携带该表专属的 columnName / 阈值 / 权重，覆盖同一份模板默认配置。
 */
@Schema(description = "质量规则模板批量应用请求")
@Data
public class QualityRuleBatchCreateRequest {

    @Schema(description = "所属质量任务 ID", example = "1234567890123456789")
    @NotNull(message = "所属质量任务不能为空")
    private Long jobId;

    @Schema(description = "来源模板 ID（必选）", example = "1234567890123456789")
    @NotNull(message = "模板不能为空")
    private Long templateId;

    @Schema(description = "逐表规则项（至少一项）")
    @NotEmpty(message = "至少需要一张表")
    @Valid
    private List<RuleItem> items;

    /**
     * 单表规则项：表 + 该表专属的字段/阈值/权重（逐表可微调）。
     */
    @Schema(description = "单表规则项")
    @Data
    public static class RuleItem {

        @Schema(description = "目标表 ID（metadata_table.id）", example = "1234567890123456789")
        @NotNull(message = "目标表不能为空")
        private Long tableId;

        @Schema(description = "该表检查字段（唯一性/值域/按字段完整性必填）")
        @Size(max = 128, message = "检查字段不能超过 128 字符")
        private String columnName;

        @Schema(description = "是否按字段检查（1 按字段，0 整表）")
        private Integer checkField = 0;

        @Schema(description = "该表规则名称（可选，缺省用模板名 + 表名生成）")
        @Size(max = 100, message = "规则名称不能超过 100 字符")
        private String name;

        @Schema(description = "自定义 SQL（模板为 CUSTOM_SQL 时逐表填写）")
        private String sqlExpression;

        @Schema(description = "警告阈值（执行结果 ≥ 此值 → 警告）")
        private BigDecimal warningThreshold;

        @Schema(description = "严重阈值（执行结果 ≥ 此值 → 严重）")
        private BigDecimal severeThreshold;

        @Schema(description = "值域下界（模板为 RANGE 时必填，SQL 模板 {min} 来源）")
        private BigDecimal rangeMin;

        @Schema(description = "值域上界（模板为 RANGE 时必填，SQL 模板 {max} 来源）")
        private BigDecimal rangeMax;

        @Schema(description = "权重（评分加权，默认 1）")
        @Min(value = 1, message = "权重最小为 1")
        private Integer weight = 1;
    }
}
