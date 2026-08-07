package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 边（节点依赖，source → target）。
 */
@Data
public class DagEdgeInfo {

    private Long id;

    private Long dagId;

    private String edgeId;

    private String sourceNodeId;

    private String targetNodeId;

    private Long createdBy;

    private LocalDateTime createdAt;
}
