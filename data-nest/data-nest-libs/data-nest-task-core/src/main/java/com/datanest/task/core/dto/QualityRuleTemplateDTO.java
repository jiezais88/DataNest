package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量规则模板 DTO（列表 / 详情响应，Sprint 6 规则模板库）。
 */
@Data
public class QualityRuleTemplateDTO {

    private Long id;

    /** 模板名称（唯一） */
    private String name;

    /** 模板类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL / PYTHON */
    private String type;

    /** 模板说明 */
    private String description;

    /** 校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等 */
    private String sqlTemplate;

    /** Python 模板脚本（def check(df) 形式；PYTHON 类型模板有值，Sprint 7 DG-10） */
    private String pythonTemplate;

    /** 结果指标名 */
    private String resultMetric;

    /** 是否内置：1 内置，0 自定义 */
    private Integer builtin;

    /** 是否启用：1 启用，0 停用 */
    private Integer enabled;

    private Long createdBy;

    private Long updatedBy;

    private String createdByName;

    private String updatedByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
