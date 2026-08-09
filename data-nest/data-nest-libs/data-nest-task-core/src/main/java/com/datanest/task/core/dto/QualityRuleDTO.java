package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "质量规则（列表/详情响应）")
@Data
public class QualityRuleDTO {

    @Schema(description = "规则 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "所属质量任务 ID（历史兼容字段，可空）", example = "1234567890123456789")
    private Long jobId;

    @Schema(description = "所属任务名（经 quality_job_rule 关联回填）")
    private String jobName;

    @Schema(description = "来源模板 ID", example = "1234567890123456789")
    private Long templateId;

    @Schema(description = "来源模板名")
    private String templateName;

    @Schema(description = "规则名称")
    private String name;

    @Schema(description = "规则类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String type;

    @Schema(description = "目标表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "目标表名（schema.table，冗余回填，便于展示）")
    private String tableName;

    @Schema(description = "目标表归属数据库名（经 metadata_table 回填，供前端编辑级联回显数据库下拉）")
    private String databaseName;

    @Schema(description = "目标表归属 Schema 名（经 metadata_table 回填，有 schema 类型才有值，供前端级联回显）")
    private String schemaName;

    @Schema(description = "目标表归属数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "目标表归属数据源名（经 datasource_connection 回填；内置 Doris 显示「Doris 数仓」）")
    private String datasourceName;

    @Schema(description = "检查字段")
    private String columnName;

    @Schema(description = "是否按字段检查（1 按字段，0 整表）")
    private Integer checkField;

    @Schema(description = "实际校验 SQL")
    private String sqlExpression;

    @Schema(description = "Python 脚本（def check(df) 返回 dict；PYTHON 类型规则有值）")
    private String pythonScript;

    @Schema(description = "警告阈值（执行结果 ≥ 此值 → 警告）")
    private BigDecimal warningThreshold;

    @Schema(description = "严重阈值（执行结果 ≥ 此值 → 严重）")
    private BigDecimal severeThreshold;

    @Schema(description = "值域下界（RANGE 类型专用）")
    private BigDecimal rangeMin;

    @Schema(description = "值域上界（RANGE 类型专用）")
    private BigDecimal rangeMax;

    @Schema(description = "结果指标名")
    private String resultMetric;

    @Schema(description = "权重（评分加权）")
    private Integer weight;

    @Schema(description = "启用状态（1 启用，0 停用）")
    private Integer enabled;

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "更新人用户名")
    private String updatedByName;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
