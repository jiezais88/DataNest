package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则 DTO（创建/更新/列表/详情，含接收用户）。
 */
@Data
public class AlertRuleDTO {

    private Long id;

    /** DAG / SYNC_JOB / COLLECT_TASK */
    private String objectType;

    private Long objectId;

    private String objectName;

    /** FAILURE / TIMEOUT / SUCCESS */
    private List<String> triggerConditions;

    /** 超时阈值（分钟） */
    private Integer timeoutMinutes;

    private Boolean enabled;

    /** 接收用户 ID 列表 */
    private List<Long> userIds;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
