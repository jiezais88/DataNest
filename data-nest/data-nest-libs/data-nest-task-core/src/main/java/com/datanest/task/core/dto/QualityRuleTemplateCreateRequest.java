package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "质量规则模板新增请求（仅自定义模板可新增）")
@Data
public class QualityRuleTemplateCreateRequest {

    @Schema(description = "模板名称")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称不能超过 100 字符")
    private String name;

    @Schema(description = "模板类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    @NotBlank(message = "模板类型不能为空")
    private String type;

    @Schema(description = "模板说明")
    @Size(max = 500, message = "模板说明不能超过 500 字符")
    private String description;

    @Schema(description = "校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等；CUSTOM_SQL 可由用户填写具体 SQL")
    private String sqlTemplate;

    @Schema(description = "Python 模板脚本（def check(df) 形式；PYTHON 类型模板用）")
    private String pythonTemplate;

    @Schema(description = "结果指标名，如 null_rate / duplicate_count / out_of_range_rate")
    @Size(max = 50, message = "结果指标名不能超过 50 字符")
    private String resultMetric;

    @Schema(description = "是否启用（1 启用，0 停用；默认 1）")
    private Integer enabled = 1;
}
