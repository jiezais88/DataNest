package com.datanest.governance.dto;

import lombok.Data;

import java.util.List;

@Data
public class ComplianceCheckRequest {

    private Long datasourceId;

    private List<Long> datasourceIds;

    private String databaseName;

    private String schemaName;

    private Long tableId;
}
