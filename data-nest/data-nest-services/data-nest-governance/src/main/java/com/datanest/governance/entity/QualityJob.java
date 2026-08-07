package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量任务（Sprint 6 配置层，D-D1）。
 * <p>
 * 任务 = 质量检查的执行单元，包含调度方式（手动/定时/任务完成自动触发，可叠加）与告警等级配置，
 * 下挂多条 {@code quality_rule}。手动是默认能力，可随时执行；定时/自动可选配。
 * 真实执行校验由下一批 {@code QualityCheckService} 接入。
 */
@Data
@TableName("quality_job")
public class QualityJob {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 任务名称（唯一） */
    private String name;

    /** 描述 */
    private String description;

    /** 可选，数据源范围（用于选表过滤） */
    private Long datasourceId;

    /** 启用状态：1 启用，0 停用 */
    private Integer enabled;

    /** 是否开定时调度（D1）：1 开，0 关 */
    private Integer scheduledEnabled;

    /** 定时 cron（scheduled_enabled=1 时必填） */
    private String cron;

    /** 是否任务完成自动触发（D1）：1 开，0 关 */
    private Integer autoTriggerEnabled;

    /** 自动触发绑定对象类型：DAG_NODE / SYNC_JOB / COLLECT_TASK */
    private String autoTriggerObjectType;

    /** 自动触发绑定对象 ID */
    private Long autoTriggerObjectId;

    /** 告警触发等级：SEVERE_ONLY / SEVERE_WARNING */
    private String alertLevel;

    /** 执行超时阈值（分钟），null = 不启用超时检测（Sprint 6+） */
    private Integer timeoutMinutes;

    /** 最近一次触发时间 */
    private LocalDateTime lastTriggerAt;

    /** 定时调度 XXL-JOB 任务 ID（注册到 data-nest-worker 组，带自身 cron） */
    private Integer xxlJobId;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
