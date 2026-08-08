package com.datanest.engineering.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 定义（一个 DAG 同步为 PowerJob 一个 Workflow）
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

    /** PowerJob 工作流 ID（P4 起唯一调度标识，旧 ds_* 列已随 V1.3.0 删除） */
    private Long powerjobWorkflowId;

    private String releaseState;         // OFFLINE / ONLINE

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
