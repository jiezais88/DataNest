package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "命名规范分页查询请求")
@Data
public class NamingStandardQueryRequest {

    @Schema(description = "关键字（模糊匹配）")
    private String keyword;

    @Schema(description = "适用对象（TABLE/COLUMN）")
    private String appliesTo;

    @Schema(description = "是否启用（1 启用 / 0 停用）")
    private Integer enabled;

    @Schema(description = "页码，从 1 开始")
    private Integer page = 1;

    @Schema(description = "每页条数")
    private Integer pageSize = 10;
}
