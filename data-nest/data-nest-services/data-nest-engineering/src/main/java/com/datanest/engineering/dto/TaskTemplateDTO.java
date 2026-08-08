package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务模板 DTO（Sprint 7 DD-09）。configTemplate 原样返回 JSON 字符串，由前端解析渲染占位符表单。
 */
@Data
public class TaskTemplateDTO {

    private Long id;

    private String name;

    private String type;

    private String category;

    private String description;

    private String configTemplate;

    private Integer enabled;

    private Long createdBy;

    /** 创建人名称（经 system-api 批量回填；内置模板为 null，前端展示「系统」） */
    private String createdByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
