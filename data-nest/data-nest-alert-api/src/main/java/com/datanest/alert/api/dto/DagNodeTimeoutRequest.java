package com.datanest.alert.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点超时通知请求。
 */
@Data
public class DagNodeTimeoutRequest {

    /** DAG ID */
    private Long dagId;

    /** 执行实例 ID */
    private Long executionId;

    /** 节点标识 */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 节点类型 */
    private String nodeType;

    /** 节点开始时间 */
    private LocalDateTime nodeStartTime;

    /** 执行实例开始时间 */
    private LocalDateTime executionStartTime;
}
