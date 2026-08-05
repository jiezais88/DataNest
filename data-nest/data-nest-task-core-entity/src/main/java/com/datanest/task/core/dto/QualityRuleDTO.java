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

    /** 目标表归属数据库名（经 metadata_table 回填，供前端编辑级联回显数据库下拉） */
    private String databaseName;

    /** 目标表归属 Schema 名（经 metadata_table 回填，有 schema 类型才有值，供前端级联回显） */
    private String schemaName;

    /** 目标表归属数据源 ID（经 metadata_table 回填，Sprint 7 方案A） */
    private Long datasourceId;

    /** 目标表归属数据源名（经 datasource_connection 回填；内置 Doris 显示 "Doris 数仓"） */
    private String datasourceName;

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
