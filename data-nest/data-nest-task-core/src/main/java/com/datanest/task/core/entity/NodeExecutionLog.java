package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点执行日志
 * 对应表 node_execution_log
 */
@Data
@TableName("node_execution_log")
public class NodeExecutionLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long executionId;

    private String nodeId;

    private String level;       // INFO / WARN / ERROR

    private String message;

    private Integer lineNum;

    private LocalDateTime createdAt;
}
