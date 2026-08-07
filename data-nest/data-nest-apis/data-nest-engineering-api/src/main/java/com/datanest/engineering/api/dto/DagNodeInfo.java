package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点定义（执行所需全量配置）。
 */
@Data
public class DagNodeInfo {

    private Long id;

    private Long dagId;

    private String nodeId;

    private String nodeName;

    private String nodeType;

    private Double positionX;

    private Double positionY;

    /** 节点配置 JSON 字符串（SQL: sqlContent / SYNC: syncJobId 等） */
    private String config;

    private Long dsTaskCode;

    /** PowerJob workflow_node_info 节点 ID（job 侧按它对齐 fetchWfInstanceInfo 返回的节点状态） */
    private Long powerjobNodeId;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
