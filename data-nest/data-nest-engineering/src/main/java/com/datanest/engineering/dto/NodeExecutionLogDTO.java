package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DAG 节点执行日志行 DTO
 */
@Data
public class NodeExecutionLogDTO {

    private Long id;

    private Long executionId;

    private String nodeId;

    private String level;

    private String message;

    private Integer lineNum;

    private LocalDateTime createdAt;
}
