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

    /** 1 成功，0 失败 */
    private Integer success;

    private String errorMessage;

    private String executedSql;

    private LocalDateTime createdAt;
}
