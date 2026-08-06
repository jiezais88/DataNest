package com.datanest.engineering.dto;

import lombok.Data;

@Data
public class DagEdgePayload {
    private Long id;
    private String edgeId;
    private String sourceNodeId;
    private String targetNodeId;
}
