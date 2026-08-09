package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "元数据采集任务")
@Data
public class CollectTaskDTO {

    @Schema(description = "任务 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据源名称")
    private String datasourceName;

    @Schema(description = "采集范围（库名列表）")
    private List<String> scope;

    @Schema(description = "采集模式（FULL/FULL_INCREMENT）")
    private String collectMode;

    @Schema(description = "触发方式（MANUAL/CRON）")
    private String triggerType;

    @Schema(description = "Cron 表达式（triggerType=CRON 时必填）")
    private String cronExpression;

    @Schema(description = "任务状态（NEVER_EXECUTED/RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;

    @Schema(description = "最近执行时间（ISO 8601）")
    private LocalDateTime lastExecuteTime;

    @Schema(description = "最近执行历史 ID", example = "1234567890123456789")
    private Long lastHistoryId;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "PowerJob 调度任务 ID", example = "1234567890123456789")
    private Long schedulerJobId;

    @Schema(description = "调度是否启用（1 启用 / 0 停用）")
    private Integer scheduleEnabled;

    @Schema(description = "下次执行时间（ISO 8601）")
    private LocalDateTime nextExecutionTime;

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
