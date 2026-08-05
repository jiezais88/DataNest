package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 质量检查批次分页查询请求（Sprint 8 执行层）。
 */
@Data
public class QualityCheckQueryRequest {

    /** 页码，默认 1 */
    private Long page = 1L;

    /** 每页条数，默认 10 */
    private Long pageSize = 10L;

    /** 质量任务 ID 过滤 */
    private Long jobId;

    /** 触发方式过滤：MANUAL / SCHEDULED / AUTO_TRIGGER */
    private String triggerType;

    /** 批次状态过滤：RUNNING / SUCCESS / PARTIAL_FAILED / FAILED */
    private String status;
}
