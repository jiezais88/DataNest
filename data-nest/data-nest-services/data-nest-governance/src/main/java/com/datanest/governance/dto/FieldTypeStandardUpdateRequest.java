package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Schema(description = "字段类型标准更新请求")
@Data
public class FieldTypeStandardUpdateRequest {

    @Schema(description = "标准名称")
    @NotBlank(message = "标准名称不能为空")
    private String name;

    @Schema(description = "标准分类")
    private String category;

    @Schema(description = "允许的字段类型列表")
    @NotEmpty(message = "允许的字段类型不能为空")
    private List<String> allowedTypes;

    @Schema(description = "标准描述")
    private String description;
}
