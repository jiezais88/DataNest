package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行队列视图对象（Sprint 11 F3 任务资源队列）
 * <p>
 * 携带当前运行数/等待任务数（PRD B6 允许秒级延迟，job 每 5s 轮询对账）；
 * 审计字段 createdBy/updatedBy + createdByName/updatedByName（后端批量回填用户名）。
 */
@Schema(description = "执行队列视图对象")
@Data
public class ExecutionQueueVO {

    @Schema(description = "队列 ID")
    private Long id;

    @Schema(description = "队列名")
    private String queueName;

    @Schema(description = "最大并发数")
    private Integer maxConcurrency;

    @Schema(description = "队列描述")
    private String description;

    @Schema(description = "系统内置队列（不可删、名称不可改）")
    private Boolean isSystem;

    @Schema(description = "当前运行数（RUNNING 执行实例数）")
    private Integer runningCount;

    @Schema(description = "等待任务数（WAITING 执行实例数）")
    private Integer waitingCount;

    @Schema(description = "绑定该队列的 DAG 数")
    private Long dagCount;

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
}