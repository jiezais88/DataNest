package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 执行实例（一次 DAG 触发对应一条记录）
 * 对应表 dag_execution
 */
@Data
@TableName("dag_execution")
public class DagExecution {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dagId;

    private Long dsProcessInstanceId;    // DS 流程实例 ID

    private String triggerType;          // MANUAL / CRON

    private String status;               // RUNNING / SUCCESS / FAILED / TERMINATED

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Long createdBy;

    private LocalDateTime createdAt;

    private String edgeSnapshot;         // 创建执行实例时的 dag_edge JSON 快照（历史视图渲染边用）

    private String errorMessage;         // 执行失败原因（如 DS 触发失败、工作流未上线等）
}
