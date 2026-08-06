package com.datanest.engineering.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DagExecutionDTO {
    private Long id;
    private Long dagId;
    private String dagName;
    private Long dsProcessInstanceId;
    private String triggerType;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String edgeSnapshot;
    private String errorMessage;
    private List<NodeExecutionDTO> nodeExecutions;
}
