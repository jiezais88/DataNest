package com.datanest.task.core.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准合规检查请求（检查范围 / 分页筛选）。
 * 从 governance 模块下沉至共享底座。
 */
@Data
public class ComplianceCheckRequest {

    private Long datasourceId;

    private List<Long> datasourceIds;

    private String databaseName;

    private String schemaName;

    private Long tableId;

    private Boolean checkNaming;

    private Boolean checkFieldType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
