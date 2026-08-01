package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sync_job_history")
public class SyncJobHistory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long syncJobId;

    /** 由 DAG 编排触发时的 dag_execution.id；手动/定时触发为 NULL */
    private Long dagExecutionId;

    private String triggerType;

    private String status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Long sourceRows;

    private Long targetRows;

    private String errorMessage;

    private Long parentHistoryId;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;
}
