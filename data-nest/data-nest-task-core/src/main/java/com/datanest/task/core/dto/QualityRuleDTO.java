package com.datanest.task.core.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量规则 DTO（列表 / 详情响应，Sprint 7 规则独立化）。
 */
@Data
public class QualityRuleDTO {

    private Long id;

    /** 所属质量任务（历史兼容字段，可空） */
    private Long jobId;

    /** 所属任务名（经 quality_job_rule 关联回填，Sprint 7 新增） */
    private String jobName;

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

    /** 值域下界（RANGE 类型专用） */
    private BigDecimal rangeMin;

    /** 值域上界（RANGE 类型专用） */
    private BigDecimal rangeMax;

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
