package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点执行状态机单点更新请求（worker 节点回调）。
 * <p>
 * expectedStatus 非空时做条件更新（当前 status 不匹配则返回 false）；
 * 其余可空字段仅在有值时覆盖。
 */
@Data
public class NodeExecutionMarkRequest {

    private String status;

    /** 可空；非空时要求当前 status 相等才更新 */
    private String expectedStatus;

    private String outputInfo;

    private String errorMessage;

    private Long durationMs;

    private Long syncJobId;

    private Long syncJobHistoryId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
