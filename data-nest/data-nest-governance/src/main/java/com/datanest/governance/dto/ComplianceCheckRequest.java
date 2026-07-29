package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ComplianceCheckRequest {

    private Long datasourceId;

    private List<Long> datasourceIds;

    private String databaseName;

    private String schemaName;

    private Long tableId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
