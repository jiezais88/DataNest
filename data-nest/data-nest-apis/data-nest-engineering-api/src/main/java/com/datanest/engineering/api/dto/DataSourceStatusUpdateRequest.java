package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源状态回写请求（job 定时刷新结果落库）。
 */
@Data
public class DataSourceStatusUpdateRequest {

    private String status;

    private String errorMessage;

    private LocalDateTime lastTestTime;
}
