package com.datanest.alert.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 执行实例信息。
 */
@Data
public class DagExecutionInfo {

    /** 执行实例 ID */
    private Long id;

    /** DAG ID */
    private Long dagId;

    /** 执行状态 */
    private String status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
