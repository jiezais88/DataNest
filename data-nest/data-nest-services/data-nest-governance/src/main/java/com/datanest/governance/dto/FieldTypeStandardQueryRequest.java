package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "字段类型标准分页查询请求")
@Data
public class FieldTypeStandardQueryRequest {

    @Schema(description = "关键字（模糊匹配）")
    private String keyword;

    @Schema(description = "标准分类")
    private String category;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
