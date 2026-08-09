package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量规则模板分页查询请求")
@Data
public class QualityRuleTemplateQueryRequest {

    @Schema(description = "关键字（模板名称模糊匹配）")
    private String keyword;

    @Schema(description = "模板类型过滤（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String type;

    @Schema(description = "是否内置过滤（1 内置，0 自定义）")
    private Integer builtin;

    @Schema(description = "是否启用过滤（1 启用，0 停用）")
    private Integer enabled;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
