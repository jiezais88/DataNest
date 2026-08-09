package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量任务 DTO（列表 / 详情响应，Sprint 6 配置层）。
 * <p>
 * scheduleStatusBadge 口径（对齐 D-D1）：仅 scheduled_enabled=1 且配了 cron 显示「已启用 / 已停用」，
 * 纯手动/自动任务显示「—」；enabled 是任务整体启停，二者独立。
 */
@Schema(description = "质量任务（列表/详情响应）")
@Data
public class QualityJobDTO {

    @Schema(description = "任务 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "任务名称")
    private String name;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "启用状态（1 启用，0 停用）")
    private Integer enabled;

    @Schema(description = "是否开定时调度（1 开，0 关）")
    private Integer scheduledEnabled;

    @Schema(description = "定时 cron 表达式")
    private String cron;

    @Schema(description = "是否任务完成自动触发（1 开，0 关）")
    private Integer autoTriggerEnabled;

    @Schema(description = "自动触发绑定对象类型（DAG_NODE/SYNC_JOB/COLLECT_TASK）")
    private String autoTriggerObjectType;

    @Schema(description = "自动触发绑定对象 ID", example = "1234567890123456789")
    private Long autoTriggerObjectId;

    @Schema(description = "自动触发绑定对象名称（同步任务/DAG 节点/采集任务，经 objectId 回填）")
    private String autoTriggerObjectName;

    @Schema(description = "告警触发等级（SEVERE_ONLY/SEVERE_WARNING）")
    private String alertLevel;

    @Schema(description = "执行超时阈值（分钟），null = 不启用超时检测")
    private Integer timeoutMinutes;

    @Schema(description = "最近触发时间（ISO 8601）")
    private LocalDateTime lastTriggerAt;

    @Schema(description = "调度状态徽章（已启用/已停用/—）")
    private String scheduleStatusBadge;

    @Schema(description = "规则数量（列表冗余统计）")
    private Long ruleCount;

    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;

    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;

    @Schema(description = "创建人用户名")
    private String createdByName;

    @Schema(description = "更新人用户名")
    private String updatedByName;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;

    @Schema(description = "任务下规则列表（仅详情返回）")
    private List<QualityRuleDTO> rules;

    @Schema(description = "任务引用的规则 ID 集合（详情返回，供前端编辑回显）")
    private List<Long> ruleIds;
}
