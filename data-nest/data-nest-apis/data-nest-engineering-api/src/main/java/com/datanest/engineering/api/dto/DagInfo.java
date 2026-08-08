package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 定义信息。
 */
@Data
public class DagInfo {

    private Long id;

    private Long projectId;

    private String name;

    private String triggerType;

    private String cronExpression;

    private Integer scheduleEnabled;

    private Integer maxParallelism;

    private String status;

    private String releaseState;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
