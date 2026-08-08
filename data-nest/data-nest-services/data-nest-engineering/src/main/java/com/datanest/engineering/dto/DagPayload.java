package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 创建/更新请求 payload（含 nodes + edges）
 * 也作为查询返回 DTO（带调度同步状态）
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

    private String releaseState;         // OFFLINE / ONLINE（ONLINE = 已同步 PowerJob）

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private String createdByName;
    private String updatedByName;

    private List<DagNodePayload> nodes;
    private List<DagEdgePayload> edges;

    /** Sprint 3 性能优化：列表页展示用节点摘要，避免前端 N+1 拉 nodes */
    private String nodeSummary;

    /** Sprint 3 性能优化：最近一次执行状态/时间，避免前端 N+1 拉 executions */
    private LatestExecution latestExecution;

    @Data
    public static class LatestExecution {
        private String status;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
