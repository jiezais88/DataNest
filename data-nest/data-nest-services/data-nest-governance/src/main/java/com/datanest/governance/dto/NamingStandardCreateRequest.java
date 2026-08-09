package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "命名规范创建请求")
@Data
public class NamingStandardCreateRequest {

    @Schema(description = "规范名称")
    @NotBlank(message = "规范名称不能为空")
    private String name;

    @Schema(description = "适用对象（TABLE/COLUMN）")
    @NotBlank(message = "适用对象不能为空")
    private String appliesTo;

    @Schema(description = "规则类型（PREFIX/SUFFIX/REGEX）")
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    @Schema(description = "规则值（如前缀字符串或正则表达式）")
    @NotBlank(message = "规则值不能为空")
    private String ruleValue;

    @Schema(description = "目标标准 ID（appliesTo=COLUMN 时必填，指向 TABLE 规范）", example = "1234567890123456789")
    private Long targetStandardId;

    @Schema(description = "优先级（数值越大越优先）")
    private Integer priority = 0;

    @Schema(description = "是否启用（1 启用 / 0 停用）")
    private Integer enabled = 1;

    @Schema(description = "规范描述")
    private String description;
}
