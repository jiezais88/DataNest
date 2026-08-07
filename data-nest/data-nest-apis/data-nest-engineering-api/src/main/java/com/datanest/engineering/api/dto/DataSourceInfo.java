package com.datanest.engineering.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源连接信息（内部契约，含 encryptedPassword，worker 建 JDBC 连接用）。
 */
@Data
public class DataSourceInfo {

    private Long id;

    private String name;

    private String type;

    private String host;

    private Integer port;

    private String databaseName;

    private String schemaName;

    private String username;

    private String encryptedPassword;

    private String description;

    private String status;

    private Integer autoCollectOnSave;

    private LocalDateTime lastTestTime;

    private String errorMessage;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
