package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CollectTaskDTO {

    private Long id;

    private String name;

    private Long datasourceId;

    private String datasourceName;

    private List<String> scope;

    private String collectMode;

    private String triggerType;

    private String cronExpression;

    private String status;

    private LocalDateTime lastExecuteTime;

    private Long lastHistoryId;

    private String description;

    private Integer xxlJobId;

    private Integer scheduleEnabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
