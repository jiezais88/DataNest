package com.datanest.engineering.dto;

import lombok.Data;

import java.util.List;

/**
 * DS WorkflowInstance（流程实例 / 执行历史）
 * 状态码：0 SUBMITTED, 1 RUNNING, 2 READY_PAUSE, 3 PAUSE, 4 READY_STOP, 5 STOP, 6 FAILURE, 7 SUCCESS, 8 NEED_FAULT_TOLERANCE, 9 KILL
 * 决策 ADR-S3-FJ：fastjson2 默认忽略未知字段，无需 @JsonIgnoreProperties
 */
@Data
public class DsWorkflowInstance {

    private Long id;
    private Long workflowDefinitionCode;
    private Integer workflowDefinitionVersion;
    private Long projectCode;
    private String name;
    private Integer state;                // DS 数字状态
    private String stateHistory;
    private String host;
    private String startTime;
    private String endTime;
    private String runTimes;
    private String triggerType;           // MANUAL / CRON / RECOVER_TOLERANCE_FAULT_PROCESS
    private String commandType;          // START_PROCESS / START_CURRENT_TASK_PROCESS / ...
    private String failureStrategy;      // END / CONTINUE
    private String workerGroup;
    private String tenantCode;
    private String duration;
    private Long durationMs;             // 部分版本返回 long
    private String executorName;
    private List<DsTaskInstance> taskInstances;
}
