package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "DAG 节点执行明细 DTO")
@Data
public class NodeExecutionDTO {
    @Schema(description = "节点执行 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "所属执行实例 ID", example = "1234567890123456789")
    private Long executionId;
    @Schema(description = "节点标识（DAG 内唯一）")
    private String nodeId;
    @Schema(description = "节点名称")
    private String nodeName;
    @Schema(description = "节点类型（SQL/SYNC/PYTHON/CONDITION/SUB_DAG）")
    private String nodeType;
    @Schema(description = "执行状态（WAITING/RUNNING/SUCCESS/FAILED/SKIPPED/TERMINATED）")
    private String status;
    /** Sprint 3 P1-2：SYNC 节点关联的 sync_job_id */
    @Schema(description = "SYNC 节点关联的同步任务 ID", example = "1234567890123456789")
    private Long syncJobId;
    /** SYNC 节点收尾时命中的 sync_job_history.id（用于查 sync_job_log 日志） */
    @Schema(description = "SYNC 节点关联的同步历史 ID（用于查同步日志）", example = "1234567890123456789")
    private Long syncJobHistoryId;
    @Schema(description = "开始时间（ISO 8601）")
    private LocalDateTime startTime;
    @Schema(description = "结束时间（ISO 8601）")
    private LocalDateTime endTime;
    @Schema(description = "耗时（毫秒）")
    private Long durationMs;
    @Schema(description = "错误信息")
    private String errorMessage;
    @Schema(description = "输出信息（如条件分支判定结果、SQL 影响行数）")
    private String outputInfo;
}
