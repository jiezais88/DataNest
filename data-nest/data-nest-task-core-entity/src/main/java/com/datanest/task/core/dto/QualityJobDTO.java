package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量任务 DTO（列表 / 详情响应，Sprint 6 配置层）。
 * <p>
 * scheduleStatusBadge 口径（对齐 D-D1）：仅 scheduled_enabled=1 且配了 cron 显示「已启用 / 已停用」，
 * 纯手动/自动任务显示「—」；enabled 是任务整体启停，二者独立。
 */
@Data
public class QualityJobDTO {

    private Long id;

    private String name;

    private String description;

    /** 启用状态：1 启用，0 停用 */
    private Integer enabled;

    /** 是否开定时调度 */
    private Integer scheduledEnabled;

    private String cron;

    /** 是否任务完成自动触发 */
    private Integer autoTriggerEnabled;

    private String autoTriggerObjectType;

    private Long autoTriggerObjectId;

    private String alertLevel;

    private LocalDateTime lastTriggerAt;

    /** 调度状态徽章：已启用 / 已停用 / — */
    private String scheduleStatusBadge;

    /** 规则数量（列表冗余统计） */
    private Long ruleCount;

    private Long createdBy;

    private Long updatedBy;

    private String createdByName;

    private String updatedByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 详情时返回的任务下规则列表 */
    private List<QualityRuleDTO> rules;

    /** 任务引用的规则 ID 集合（详情返回，供前端编辑回显，Sprint 7） */
    private List<Long> ruleIds;
}
