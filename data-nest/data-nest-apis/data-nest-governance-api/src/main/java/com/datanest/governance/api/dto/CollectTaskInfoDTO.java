package com.datanest.governance.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 采集任务定义（全字段，对齐 collect_task 表；时间字段为 ISO 字符串）。
 */
@Data
public class CollectTaskInfoDTO {

    private Long id;

    private String name;

    private Long datasourceId;

    private String datasourceName;

    /** 采集范围（schema/database 列表） */
    private List<String> scope;

    private String collectMode;

    private String triggerType;

    private String cronExpression;

    private String status;

    /** ISO 格式时间字符串 */
    private String lastExecuteTime;

    private Long lastHistoryId;

    private String description;

    /** PowerJob 调度任务 ID（旧 xxl_job_id 列保留至切流清理，不再读写） */
    private Long schedulerJobId;

    private Integer scheduleEnabled;

    /** ISO 格式时间字符串 */
    private String nextExecutionTime;

    private Long createdBy;

    private Long updatedBy;

    /** ISO 格式时间字符串 */
    private String createdAt;

    /** ISO 格式时间字符串 */
    private String updatedAt;
}
