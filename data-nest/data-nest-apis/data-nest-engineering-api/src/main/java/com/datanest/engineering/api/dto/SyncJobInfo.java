package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务定义与状态（执行所需全部配置）。
 */
@Data
public class SyncJobInfo {

    private Long id;

    private String name;

    private Long sourceDatasourceId;

    private Long targetDatasourceId;

    private String sourceDatabase;

    private String sourceSchema;

    private List<String> sourceTables;

    private String syncMode;

    private String incrementalField;

    private String triggerType;

    private String cronExpression;

    private Integer retryTimes;

    private Integer retryInterval;

    private List<FieldMappingItemDTO> fieldMapping;

    private String status;

    private String executionStatus;

    private String targetDatabase;

    private String targetTable;

    private LocalDateTime nextExecutionTime;

    private Integer scheduleEnabled;

    /** 多表结构化配置 JSON 字符串 */
    private String sourceTablesDetail;

    private Integer readRateLimitMbps;

    private Integer writeRateLimitRowsPerSecond;

    private Integer rateLimitEnabled;

    /** PowerJob jobId */
    private Long schedulerJobId;

    private String description;

    private LocalDateTime lastExecuteTime;

    private Long lastHistoryId;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
