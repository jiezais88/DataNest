package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 创建/更新请求 payload（含 nodes + edges）
 * 也作为查询返回 DTO（带 ds 同步状态）
 * 决策 ADR-S3-FJ：使用 fastjson2 序列化
 */
@Data
public class DagPayload {

    private Long id;
    private Long projectId;
    private String name;
    private String triggerType;          // MANUAL / CRON
    private String cronExpression;
    private Boolean scheduleEnabled;     // null=false
    private Integer maxParallelism;      // 默认 3
    private String status;               // ENABLED / DISABLED

    private Long dsProjectCode;
    private Long dsProcessDefinitionId;
    private Long dsProcessDefinitionCode;
    private Long dsScheduleId;
    private String releaseState;         // OFFLINE / ONLINE

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<DagNodePayload> nodes;
    private List<DagEdgePayload> edges;
}
