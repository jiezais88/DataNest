package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量检查批次 DTO（Sprint 8 执行层）。
 * <p>
 * 列表展示批次概览（规则数/成功数）；详情可带明细列表。
 */
@Schema(description = "质量检查批次")
@Data
public class QualityCheckBatchDTO {

    @Schema(description = "批次 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "质量任务 ID", example = "1234567890123456789")
    private Long jobId;

    @Schema(description = "质量任务名")
    private String jobName;

    @Schema(description = "触发方式（MANUAL/SCHEDULED/AUTO_TRIGGER）")
    private String triggerType;

    @Schema(description = "批次状态（RUNNING/SUCCESS/PARTIAL_FAILED/FAILED）")
    private String status;

    @Schema(description = "开始时间（ISO 8601）")
    private LocalDateTime startedAt;

    @Schema(description = "结束时间（ISO 8601）")
    private LocalDateTime endedAt;

    @Schema(description = "执行耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "规则总数")
    private Integer ruleCount;

    @Schema(description = "成功规则数（执行层）")
    private Integer successCount;

    @Schema(description = "失败规则数（执行层）")
    private Integer failedCount;

    @Schema(description = "通过规则数（按 result_level=PASS 聚合，判定层）")
    private Integer passCount;

    @Schema(description = "警告规则数（按 result_level=WARNING 聚合，判定层）")
    private Integer warningCount;

    @Schema(description = "严重规则数（按 result_level=SEVERE 聚合，判定层）")
    private Integer severeCount;

    @Schema(description = "不可用规则数（按 result_level=UNAVAILABLE 聚合，判定层）")
    private Integer unavailableCount;

    @Schema(description = "明细列表（仅详情接口回填）")
    private List<QualityCheckDetailDTO> details;

    @Schema(description = "关联的告警记录（仅详情接口回填，经 alert-service 按 quality_batch_id 远程反查）")
    private List<com.datanest.alert.api.dto.AlertHistoryDTO> alertHistories;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
