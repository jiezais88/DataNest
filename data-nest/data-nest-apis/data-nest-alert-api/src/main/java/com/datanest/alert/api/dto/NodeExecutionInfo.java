package com.datanest.alert.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点执行信息。
 */
@Data
public class NodeExecutionInfo {

    /** 节点执行记录 ID */
    private Long id;

    /** 执行实例 ID */
    private Long executionId;

    /** DAG ID */
    private Long dagId;

    /** 节点标识 */
    private String nodeId;

    /** 节点名称 */
    private String nodeName;

    /** 节点类型 */
    private String nodeType;

    /** 执行状态 */
    private String status;

    /** 错误信息 */
    private String errorMessage;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
