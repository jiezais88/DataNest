package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 质量规则分页查询请求（Sprint 7 规则独立化）。
 */
@Data
public class QualityRuleQueryRequest {

    /** 页码，默认 1 */
    private Long page = 1L;

    /** 每页条数，默认 10 */
    private Long pageSize = 10L;

    /** 关键字（规则名称模糊匹配） */
    private String keyword;

    /** 规则类型过滤：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
    private String type;

    /** 启用状态过滤 */
    private Integer enabled;

    /** 所属任务过滤（经 quality_job_rule 关联表过滤） */
    private Long jobId;

    /** 目标表 ID 过滤 */
    private Long tableId;
}
