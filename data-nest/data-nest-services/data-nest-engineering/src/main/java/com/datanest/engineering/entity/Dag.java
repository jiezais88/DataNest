package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 定义（一个 DAG 同步为 DS 一个 ProcessDefinition）
 * 对应表 dag
 */
@Data
@TableName("dag")
public class Dag {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;

    private String name;

    private String triggerType;          // MANUAL / CRON

    private String cronExpression;

    private Integer scheduleEnabled;     // 0 / 1

    private Integer maxParallelism;      // 默认 3

    private String status;               // ENABLED / DISABLED

    private Long dsProjectCode;

    private Long dsProcessDefinitionId;

    private Long dsProcessDefinitionCode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long dsScheduleId;

    /** PowerJob 工作流 ID（替代 dsProcessDefinitionCode，切流后生效，旧列保留至切流清理） */
    private Long powerjobWorkflowId;

    private String releaseState;         // OFFLINE / ONLINE

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
