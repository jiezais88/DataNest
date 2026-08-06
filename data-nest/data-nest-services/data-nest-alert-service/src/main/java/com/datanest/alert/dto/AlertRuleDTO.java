package com.datanest.alert.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则 DTO（创建/更新/列表/详情，含接收用户）。
 */
@Data
public class AlertRuleDTO {

    private Long id;

    /** 规则名称（用户自定义，必填；同一 object_type 下唯一） */
    private String name;

    /** DAG / SYNC_JOB / COLLECT_TASK / QUALITY */
    private String objectType;

    /** 告警对象 ID 列表（多选） */
    private List<Long> objectIds;

    /** 对象名称冗余，便于列表展示；多对象时以「、」拼接 */
    private String objectName;

    /** FAILURE / TIMEOUT / SUCCESS */
    private List<String> triggerConditions;

    /** 超时阈值（分钟） */
    private Integer timeoutMinutes;

    private Boolean enabled;

    /** 接收用户 ID 列表 */
    private List<Long> userIds;

    private String createdByName;

    private String updatedByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
