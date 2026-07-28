package com.datanest.engineering.dto;

import java.time.LocalDateTime;

public class SyncJobHistoryDTO {

    private Long id;
    private Long syncJobId;
    private String triggerType;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private Long durationSeconds;
    private Double throughputRowsPerSecond;
    private Long sourceRows;
    private Long targetRows;
    private String errorMessage;
    private String sourceDatabase;
    private String sourceSchema;
    private String sourceTable;
    private String targetDatabase;
    private String targetTable;
    private String syncMode;
    private String incrementalField;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSyncJobId() {
        return syncJobId;
    }

    public void setSyncJobId(Long syncJobId) {
        this.syncJobId = syncJobId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Long getSourceRows() {
        return sourceRows;
    }

    public void setSourceRows(Long sourceRows) {
        this.sourceRows = sourceRows;
    }

    public Long getTargetRows() {
        return targetRows;
    }

    public void setTargetRows(Long targetRows) {
        this.targetRows = targetRows;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Double getThroughputRowsPerSecond() {
        return throughputRowsPerSecond;
    }

    public void setThroughputRowsPerSecond(Double throughputRowsPerSecond) {
        this.throughputRowsPerSecond = throughputRowsPerSecond;
    }

    public String getSourceDatabase() {
        return sourceDatabase;
    }

    public void setSourceDatabase(String sourceDatabase) {
        this.sourceDatabase = sourceDatabase;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getTargetDatabase() {
        return targetDatabase;
    }

    public void setTargetDatabase(String targetDatabase) {
        this.targetDatabase = targetDatabase;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(String syncMode) {
        this.syncMode = syncMode;
    }

    public String getIncrementalField() {
        return incrementalField;
    }

    public void setIncrementalField(String incrementalField) {
        this.incrementalField = incrementalField;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
