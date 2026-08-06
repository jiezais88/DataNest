package com.datanest.governance.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectChangeDetailDTO {

    private Long id;

    private Long historyId;

    private String changeType;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String columnName;

    private String oldValue;

    private String newValue;

    private LocalDateTime createdAt;
}
