package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NodeExecutionDTO {
    private Long id;
    private Long executionId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private String status;
    private Long dsTaskInstanceId;
    /** Sprint 3 P1-2：SYNC 节点关联的 sync_job_id */
    private Long syncJobId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String errorMessage;
    private String outputInfo;
}
