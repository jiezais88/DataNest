package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单表规则 + 最近一次检查结果 DTO（Sprint 6 NG8 元数据「质量」页签）。
 * <p>
 * 按表查询该表所有启用规则，逐条回填最近一次检查的分级与结果值（rule_id 取最近一条）。
 */
@Schema(description = "单表规则 + 最近一次检查结果")
@Data
public class QualityTableRuleResultDTO {

    @Schema(description = "规则 ID", example = "1234567890123456789")
    private Long ruleId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String ruleType;

    @Schema(description = "所属任务名（可被多任务引用，逗号拼接）")
    private String jobName;

    @Schema(description = "检查字段")
    private String columnName;

    @Schema(description = "权重")
    private Integer weight;

    @Schema(description = "最近一次结果值")
    private BigDecimal resultValue;

    @Schema(description = "最近一次分级（PASS/WARNING/SEVERE/UNAVAILABLE）")
    private String resultLevel;

    @Schema(description = "最近一次检查时间（取明细 created_at，ISO 8601）")
    private LocalDateTime lastCheckedAt;

    @Schema(description = "最近一次执行是否成功（1 成功，0 失败）")
    private Integer success;
}
