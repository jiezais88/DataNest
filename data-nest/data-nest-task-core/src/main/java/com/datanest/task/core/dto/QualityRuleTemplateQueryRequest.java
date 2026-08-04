package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 质量规则模板分页查询请求（Sprint 6 规则模板库）。
 */
@Data
public class QualityRuleTemplateQueryRequest {

    /** 关键字：模板名称模糊匹配 */
    private String keyword;

    /** 模板类型过滤：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
    private String type;

    /** 是否内置过滤：1 内置，0 自定义 */
    private Integer builtin;

    /** 是否启用过滤：1 启用，0 停用 */
    private Integer enabled;

    private Integer page = 1;

    private Integer pageSize = 10;
}
