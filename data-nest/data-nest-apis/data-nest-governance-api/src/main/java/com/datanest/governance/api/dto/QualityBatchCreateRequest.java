package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量检查批次创建请求（初始化 RUNNING 批次）。
 */
@Data
public class QualityBatchCreateRequest {

    /** 质量任务 ID（单规则执行为空） */
    private Long jobId;

    /** 任务名称快照（单规则执行先落临时名，明细落库后按规则名+表名更新） */
    private String jobName;

    /** 触发方式：MANUAL / SCHEDULED / AUTO_TRIGGER */
    private String triggerType;
}
