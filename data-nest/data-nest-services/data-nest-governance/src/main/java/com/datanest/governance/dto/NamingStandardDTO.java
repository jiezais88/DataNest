package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "命名规范（定义表/字段命名规则）")
@Data
public class NamingStandardDTO {

    @Schema(description = "规范 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "规范名称")
    private String name;

    @Schema(description = "适用对象（TABLE/COLUMN）")
    private String appliesTo;

    @Schema(description = "规则类型（PREFIX/SUFFIX/REGEX）")
    private String ruleType;

    @Schema(description = "规则值（如前缀字符串或正则表达式）")
    private String ruleValue;

    @Schema(description = "目标标准 ID（appliesTo=COLUMN 时必填，指向 TABLE 规范）", example = "1234567890123456789")
    private Long targetStandardId;

    @Schema(description = "目标标准名称")
    private String targetStandardName;

    @Schema(description = "优先级（数值越大越优先）")
    private Integer priority;

    @Schema(description = "是否启用（1 启用 / 0 停用）")
    private Integer enabled;

    @Schema(description = "规范描述")
    private String description;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "更新人用户名")
    private String updatedByName;
}
