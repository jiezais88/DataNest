package com.datanest.task.core.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量规则 DTO（列表 / 详情响应，Sprint 6 配置层）。
 */
@Data
public class QualityRuleDTO {

    private Long id;

    private Long jobId;

    private Long templateId;

    private String templateName;

    private String name;

    private String type;

    private Long tableId;

    /** 目标表名（schema.table，冗余回填，便于展示） */
    private String tableName;

    private String columnName;

    private Integer checkField;

    private String sqlExpression;

    private BigDecimal warningThreshold;

    private BigDecimal severeThreshold;

    private String resultMetric;

    private Integer weight;

    private Integer enabled;

    private Long createdBy;

    private Long updatedBy;

    private String createdByName;

    private String updatedByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
