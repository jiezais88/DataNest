package com.datanest.engineering.dto;

import lombok.Data;

/**
 * DS WorkflowDefinition 概要（list 接口返回的 data 元素）
 * 决策 ADR-S3-FJ：fastjson2 默认忽略未知字段
 */
@Data
public class DsWorkflowDefinition {

    private Long id;
    private Long code;
    private String name;
    private Integer version;
    private String releaseState;     // OFFLINE / ONLINE
    private String description;
    private Long projectCode;
    private String userName;
    private String scheduleReleaseState;
    private String schedule;
    private String cron;
    private String executionType;    // PARALLEL / SERIAL_*
    private String createTime;
    private String updateTime;
}
