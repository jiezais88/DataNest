package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 执行实例信息。
 */
@Data
public class DagExecutionInfo {

    private Long id;

    private Long dagId;

    /** PowerJob 工作流实例 ID（旧 dsProcessInstanceId 已随 P4 切流清理删除） */
    private Long powerjobWfInstanceId;

    private String triggerType;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Long createdBy;

    private LocalDateTime createdAt;

    /** 创建执行实例时的 dag_edge JSON 快照 */
    private String edgeSnapshot;

    private String errorMessage;

    /** 本次执行解析后的参数键值对 JSON 字符串 */
    private String resolvedParams;
}
