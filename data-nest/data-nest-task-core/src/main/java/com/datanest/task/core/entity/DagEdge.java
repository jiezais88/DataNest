package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 边（节点依赖关系，source → target）
 * 对应表 dag_edge
 */
@Data
@TableName("dag_edge")
public class DagEdge {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dagId;

    private String edgeId;

    private String sourceNodeId;

    private String targetNodeId;

    private Long createdBy;

    private LocalDateTime createdAt;
}
