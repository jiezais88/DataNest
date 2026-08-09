package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "质量规则模板（列表/详情响应）")
@Data
public class QualityRuleTemplateDTO {

    @Schema(description = "模板 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "模板名称（唯一）")
    private String name;

    @Schema(description = "模板类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String type;

    @Schema(description = "模板说明")
    private String description;

    @Schema(description = "校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等")
    private String sqlTemplate;

    @Schema(description = "Python 模板脚本（def check(df) 形式；PYTHON 类型模板有值）")
    private String pythonTemplate;

    @Schema(description = "结果指标名")
    private String resultMetric;

    @Schema(description = "是否内置（1 内置，0 自定义）")
    private Integer builtin;

    @Schema(description = "是否启用（1 启用，0 停用）")
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
