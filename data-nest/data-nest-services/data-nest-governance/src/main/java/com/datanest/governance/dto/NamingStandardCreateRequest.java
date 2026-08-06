package com.datanest.governance.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NamingStandardCreateRequest {

    @NotBlank(message = "规范名称不能为空")
    private String name;

    @NotBlank(message = "适用对象不能为空")
    private String appliesTo;

    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    @NotBlank(message = "规则值不能为空")
    private String ruleValue;

    private Long targetStandardId;

    private Integer priority = 0;

    private Integer enabled = 1;

    private String description;
}
