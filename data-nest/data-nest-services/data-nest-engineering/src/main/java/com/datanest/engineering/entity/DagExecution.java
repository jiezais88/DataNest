package com.datanest.engineering.entity;

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

    /** PowerJob 工作流实例 ID（P4 起唯一调度标识，旧 ds_process_instance_id 列已随 V1.3.0 删除） */
    private Long powerjobWfInstanceId;

    private String triggerType;          // MANUAL / CRON

    private String status;               // RUNNING / SUCCESS / FAILED / TERMINATED

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Long createdBy;

    private LocalDateTime createdAt;

    private String edgeSnapshot;         // 创建执行实例时的 dag_edge JSON 快照（历史视图渲染边用）

    private String errorMessage;         // 执行失败原因（如调度触发失败、工作流未上线等）

    /**
     * Sprint 4 Phase 1：本次执行解析后的参数键值对 JSON 字符串。
     * 参考 scope/source_tables_detail 等列，统一用 TEXT 存储以避免 PG jsonb 与 MyBatis-Plus 的类型冲突。
     */
    private String resolvedParams;
}
