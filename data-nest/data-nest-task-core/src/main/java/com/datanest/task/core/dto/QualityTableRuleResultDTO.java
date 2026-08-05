package com.datanest.task.core.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单表规则 + 最近一次检查结果 DTO（Sprint 6 NG8 元数据「质量」页签）。
 * <p>
 * 按表查询该表所有启用规则，逐条回填最近一次检查的分级与结果值（rule_id 取最近一条）。
 */
@Data
public class QualityTableRuleResultDTO {

    /** 规则 ID */
    private Long ruleId;

    /** 规则名称 */
    private String ruleName;

    /** 规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
    private String ruleType;

    /** 所属任务名（可被多任务引用，逗号拼接） */
    private String jobName;

    /** 检查字段 */
    private String columnName;

    /** 权重 */
    private Integer weight;

    /** 最近一次结果值 */
    private BigDecimal resultValue;

    /** 最近一次分级：PASS/WARNING/SEVERE/UNAVAILABLE */
    private String resultLevel;

    /** 最近一次检查时间（取明细 created_at） */
    private LocalDateTime lastCheckedAt;

    /** 最近一次执行是否成功：1 成功，0 失败 */
    private Integer success;
}
