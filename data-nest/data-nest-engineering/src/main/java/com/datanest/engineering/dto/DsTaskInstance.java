package com.datanest.engineering.dto;

import lombok.Data;

/**
 * DS TaskInstance（任务实例 / 单个节点执行记录）
 * 状态码：0 SUBMITTED, 1 RUNNING, 2 READY_PAUSE, 3 PAUSE, 4 READY_STOP, 5 STOP, 6 FAILURE, 7 SUCCESS, 8 NEED_FAULT_TOLERANCE, 9 KILL
 * 决策 ADR-S3-FJ：fastjson2 默认忽略未知字段，无需 @JsonIgnoreProperties
 */
@Data
public class DsTaskInstance {

    private Long id;
    private String name;
    private String taskType;
    private Integer state;        // DS 数字状态
    private String host;
    private String startTime;
    private String endTime;
    private Long duration;        // ms
    private Long workflowInstanceId;
    private String executorName;
    private String logPath;
    private String errorMessage;
}
