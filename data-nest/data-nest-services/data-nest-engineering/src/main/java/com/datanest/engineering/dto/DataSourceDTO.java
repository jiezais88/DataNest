package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "数据源 DTO")
public class DataSourceDTO {

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "数据源名称")
    private String name;
    @Schema(description = "数据源类型（MYSQL/POSTGRESQL/DORIS/ORACLE/SQLSERVER）")
    private String type;
    @Schema(description = "主机地址")
    private String host;
    @Schema(description = "端口")
    private Integer port;
    @Schema(description = "数据库名")
    private String databaseName;
    @Schema(description = "Schema 名")
    private String schemaName;
    @Schema(description = "用户名")
    private String username;
    @Schema(description = "脱敏后的密码（不回传明文）")
    private String passwordMasked;
    @Schema(description = "描述")
    private String description;
    @Schema(description = "连接状态（NORMAL/ERROR）")
    private String status;
    @Schema(description = "最近连接测试时间（ISO 8601）")
    private LocalDateTime lastTestTime;
    @Schema(description = "错误信息")
    private String errorMessage;
    @Schema(description = "保存后是否自动触发元数据采集（1=是，0=否）")
    private Integer autoCollectOnSave;
    @Schema(description = "提示信息（如连接测试结果说明）")
    private String message;
    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间（ISO 8601）")
    private LocalDateTime updatedAt;
    @Schema(description = "创建人 ID", example = "1234567890123456789")
    private Long createdBy;
    @Schema(description = "更新人 ID", example = "1234567890123456789")
    private Long updatedBy;
    @Schema(description = "创建人用户名")
    private String createdByName;
    @Schema(description = "更新人用户名")
    private String updatedByName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordMasked() {
        return passwordMasked;
    }

    public void setPasswordMasked(String passwordMasked) {
        this.passwordMasked = passwordMasked;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(LocalDateTime lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getAutoCollectOnSave() {
        return autoCollectOnSave;
    }

    public void setAutoCollectOnSave(Integer autoCollectOnSave) {
        this.autoCollectOnSave = autoCollectOnSave;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public String getUpdatedByName() {
        return updatedByName;
    }

    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }
}
