package com.datanest.alert.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DAG 告警配置 DTO
 */
@Data
public class DagAlertConfigPayload {

    private Long id;

    private Boolean enabled;

    private String recipients;

    private List<String> triggerConditions;

    private Integer timeoutMinutes;

    private Long dagId;          // Sprint 4 review：按 DAG 覆盖；null 表示全局默认

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
