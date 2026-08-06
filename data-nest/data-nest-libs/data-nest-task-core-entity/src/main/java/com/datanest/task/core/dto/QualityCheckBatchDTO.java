package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量检查批次 DTO（Sprint 8 执行层）。
 * <p>
 * 列表展示批次概览（规则数/成功数）；详情可带明细列表。
 */
@Data
public class QualityCheckBatchDTO {

    private Long id;

    private Long jobId;

    private String jobName;

    /** 触发方式：MANUAL / SCHEDULED / AUTO_TRIGGER */
    private String triggerType;

    /** 批次状态：RUNNING / SUCCESS / PARTIAL_FAILED / FAILED */
    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private Long durationMs;

    private String errorMessage;

    /** 规则总数 */
    private Integer ruleCount;

    /** 成功规则数 */
    private Integer successCount;

    /** 失败规则数 */
    private Integer failedCount;

    /** 通过规则数（按 result_level=PASS 聚合，判定层） */
    private Integer passCount;

    /** 警告规则数（按 result_level=WARNING 聚合，判定层） */
    private Integer warningCount;

    /** 严重规则数（按 result_level=SEVERE 聚合，判定层） */
    private Integer severeCount;

    /** 不可用规则数（按 result_level=UNAVAILABLE 聚合，判定层） */
    private Integer unavailableCount;

    /** 明细列表（仅详情接口回填） */
    private List<QualityCheckDetailDTO> details;

    /** 关联的告警记录（仅详情接口回填，经 alert-service 按 quality_batch_id 远程反查） */
    private List<com.datanest.alert.api.dto.AlertHistoryDTO> alertHistories;

    private LocalDateTime createdAt;
}
