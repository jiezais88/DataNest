package com.datanest.task.core.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量检查规则明细 DTO（Sprint 8 执行层）。
 */
@Data
public class QualityCheckDetailDTO {

    private Long id;

    private Long batchId;

    private Long ruleId;

    private String ruleName;

    private String ruleType;

    private Long tableId;

    private String tableName;

    private String resultMetric;

    private BigDecimal resultValue;

    /** 分级判定：PASS / WARNING / SEVERE / UNAVAILABLE */
    private String resultLevel;

    /** 警告阈值（判定依据，经 ruleId 回填，便于展示"为什么严重"） */
    private BigDecimal warningThreshold;

    /** 严重阈值（判定依据，经 ruleId 回填） */
    private BigDecimal severeThreshold;

    /** 1 成功，0 失败 */
    private Integer success;

    private String errorMessage;

    private String executedSql;

    private LocalDateTime createdAt;
}
