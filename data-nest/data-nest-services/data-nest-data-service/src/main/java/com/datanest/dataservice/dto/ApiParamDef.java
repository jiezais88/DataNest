package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * API 参数化筛选定义（params_json.filters 元素）。
 * <p>
 * type = EQ：等值筛选（调用参数 field=value）；RANGE：范围筛选（调用参数 min_field / max_field）。
 */
@Data
@Schema(description = "API 参数化筛选定义")
public class ApiParamDef {

    /** 等值筛选 */
    public static final String TYPE_EQ = "EQ";
    /** 范围筛选 */
    public static final String TYPE_RANGE = "RANGE";

    @Schema(description = "字段名（列标识符）")
    @NotBlank(message = "筛选字段不能为空")
    private String field;

    @Schema(description = "筛选类型：EQ 等值 / RANGE 范围")
    @NotBlank(message = "筛选类型不能为空")
    private String type;
}
