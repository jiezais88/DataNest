package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量检查批次（Sprint 8 执行层）。
 * <p>
 * 一次质量任务执行（或单规则执行）对应一条批次，记录触发方式、整体状态、起止时间与耗时。
 * 批次下挂多条 {@code quality_check_detail}（每条规则一条）。
 * 本次仅记录结果值，不实现分级/评分。
 */
@Data
@TableName("quality_check_batch")
public class QualityCheckBatch {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 质量任务 ID（单规则执行为空） */
    private Long jobId;

    /** 任务名称快照 */
    private String jobName;

    /** 触发方式：MANUAL / SCHEDULED / AUTO_TRIGGER */
    private String triggerType;

    /** 批次状态：RUNNING / SUCCESS / PARTIAL_FAILED / FAILED */
    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 整体错误信息（非规则级） */
    private String errorMessage;

    private LocalDateTime createdAt;
}
