package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量执行计划请求（按任务）。
 */
@Data
public class QualityExecutionPlanRequest {

    /** 质量任务 ID */
    private Long jobId;
}
