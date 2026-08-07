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

    /** PowerJob 调度任务 ID（旧 xxl_job_id 列保留至切流清理，不再读写） */
    private Long schedulerJobId;

    private Integer scheduleEnabled;

    private LocalDateTime nextExecutionTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private String createdByName;

    private String updatedByName;
}
