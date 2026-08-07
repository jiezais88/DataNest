package com.datanest.engineering.api.dto;

import lombok.Data;

/**
 * 新建 RUNNING 同步执行历史请求（覆盖 init 与 retry 两种插入）。
 */
@Data
public class SyncHistoryCreateRequest {

    private Long syncJobId;

    private String triggerType;

    /** 由 DAG 编排触发时的 dag_execution.id；可空 */
    private Long dagExecutionId;

    /** 重试历史关联的来源历史 id；可空 */
    private Long parentHistoryId;

    /** 已重试次数；可空（默认 0） */
    private Integer retryCount;
}
