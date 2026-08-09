package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "字段类型标准")
@Data
public class FieldTypeStandardDTO {

    @Schema(description = "标准 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "标准名称")
    private String name;

    @Schema(description = "标准分类")
    private String category;

    @Schema(description = "允许的字段类型列表")
    private List<String> allowedTypes;

    @Schema(description = "标准描述")
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
