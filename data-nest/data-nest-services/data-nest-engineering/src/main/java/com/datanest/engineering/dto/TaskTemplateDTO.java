package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务模板 DTO（Sprint 7 DD-09）。configTemplate 原样返回 JSON 字符串，由前端解析渲染占位符表单。
 */
@Schema(description = "任务模板 DTO")
@Data
public class TaskTemplateDTO {

    @Schema(description = "模板 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "模板名称")
    private String name;

    @Schema(description = "模板类型（SYNC/COLLECT）")
    private String type;

    @Schema(description = "模板来源（BUILTIN/CUSTOM）")
    private String category;

    @Schema(description = "模板说明")
    private String description;

    @Schema(description = "模板 JSON 原文（由前端解析渲染占位符表单）")
    private String configTemplate;

    @Schema(description = "是否启用（1=启用，0=停用）")
    private Integer enabled;

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    /** 创建人名称（经 system-api 批量回填；内置模板为 null，前端展示「系统」） */
    @Schema(description = "创建人名称（内置模板为 null，前端展示「系统」）")
    private String createdByName;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
}
