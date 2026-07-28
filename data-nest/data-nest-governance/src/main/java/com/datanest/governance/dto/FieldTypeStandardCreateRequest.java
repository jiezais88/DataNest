package com.datanest.governance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class FieldTypeStandardCreateRequest {

    @NotBlank(message = "标准名称不能为空")
    private String name;

    private String category;

    @NotEmpty(message = "允许的字段类型不能为空")
    private List<String> allowedTypes;

    private String description;
}
