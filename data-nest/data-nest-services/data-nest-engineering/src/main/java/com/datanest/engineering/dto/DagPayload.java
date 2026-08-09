package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 创建/更新请求 payload（含 nodes + edges）
 * 也作为查询返回 DTO（带调度同步状态）
 * 决策 ADR-S3-FJ：使用 fastjson2 序列化
 */
@Schema(description = "DAG 创建/更新请求 payload（含节点与边），也作为查询返回 DTO")
@Data
public class DagPayload {

    @Schema(description = "DAG ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "所属项目 ID", example = "1234567890123456789")
    private Long projectId;
    @Schema(description = "DAG 名称")
    private String name;
    @Schema(description = "触发方式（MANUAL/CRON）")
    private String triggerType;          // MANUAL / CRON
    @Schema(description = "Cron 表达式")
    private String cronExpression;
    @Schema(description = "调度是否启用（null=false）")
    private Boolean scheduleEnabled;     // null=false
    @Schema(description = "最大并行度（默认 3）")
    private Integer maxParallelism;      // 默认 3
    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;               // ENABLED / DISABLED

    @Schema(description = "发布状态（OFFLINE/ONLINE，ONLINE = 已同步 PowerJob）")
    private String releaseState;         // OFFLINE / ONLINE（ONLINE = 已同步 PowerJob）

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;
    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;
    @Schema(description = "创建人用户名")
    private String createdByName;
    @Schema(description = "更新人用户名")
    private String updatedByName;

    @Schema(description = "节点列表")
    private List<DagNodePayload> nodes;
    @Schema(description = "边列表")
    private List<DagEdgePayload> edges;

    /** Sprint 3 性能优化：列表页展示用节点摘要，避免前端 N+1 拉 nodes */
    @Schema(description = "节点摘要（列表页展示用）")
    private String nodeSummary;

    /** Sprint 3 性能优化：最近一次执行状态/时间，避免前端 N+1 拉 executions */
    @Schema(description = "最近一次执行摘要（状态/时间）")
    private LatestExecution latestExecution;

    @Schema(description = "最近一次执行摘要")
    @Data
    public static class LatestExecution {
        @Schema(description = "执行状态（RUNNING/SUCCESS/FAILED/TERMINATED）")
        private String status;
        @Schema(description = "开始时间（ISO 8601）")
        private LocalDateTime startTime;
        @Schema(description = "结束时间（ISO 8601）")
        private LocalDateTime endTime;
    }
}
