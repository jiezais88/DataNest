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

    /** 开始时间下界（ISO 8601 字符串，如 2026-08-02T12:00:00），按 started_at 过滤 */
    private String startTimeFrom;

    /** 开始时间上界（ISO 8601 字符串） */
    private String startTimeTo;
}
