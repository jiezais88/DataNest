package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 质量任务分页查询请求（Sprint 6 配置层）。
 */
@Data
public class QualityJobQueryRequest {

    /** 页码，默认 1 */
    private Long page = 1L;

    /** 每页条数，默认 10 */
    private Long pageSize = 10L;

    /** 关键字（名称/描述模糊匹配） */
    private String keyword;

    /** 启用状态过滤 */
    private Integer enabled;

    /** 是否开定时调度过滤 */
    private Integer scheduledEnabled;
}
