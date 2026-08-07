package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 触发同步任务执行请求。
 */
@Data
public class SyncJobTriggerRequest {

    /** 由 DAG 编排触发时的 dag_execution.id；可空 */
    private Long dagExecutionId;

    /** 触发来源（MANUAL / CRON / DAG / RETRY 等） */
    private String triggerType;
}
