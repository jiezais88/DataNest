package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点执行实例（DAG 内每个节点一条）
 * 对应表 node_execution
 */
@Data
@TableName("node_execution")
public class NodeExecution {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long executionId;

    /** Sprint 4 review：超时扫描 join dag_execution 时临时带入，非持久化字段 */
    @TableField(exist = false)
    private Long dagId;

    private String nodeId;

    private String nodeName;

    private String nodeType;

    private String status;               // WAITING / RUNNING / SUCCESS / FAILED / SKIPPED

    private Long dsTaskInstanceId;

    /** Sprint 3 P1-2：SYNC 节点关联的 sync_job_id，用于 DagExecutionSyncService 反查 sync_job_history 同步终态 */
    private Long syncJobId;

    /** SYNC 节点收尾时命中的 sync_job_history.id，用于按执行实例查 sync_job_log 日志 */
    private Long syncJobHistoryId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private String errorMessage;

    private String outputInfo;

    /** Sprint 3 性能7：MyBatis-Plus 乐观锁字段 */
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}
