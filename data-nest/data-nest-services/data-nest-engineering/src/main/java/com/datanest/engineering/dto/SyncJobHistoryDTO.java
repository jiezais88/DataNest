package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "同步任务执行历史 DTO")
public class SyncJobHistoryDTO {

    @Schema(description = "执行历史 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "同步任务 ID", example = "1234567890123456789")
    private Long syncJobId;
    @Schema(description = "任务名称")
    private String taskName;
    /** 由 DAG 编排触发时的 dag_execution.id；手动/定时触发为 null */
    @Schema(description = "DAG 编排触发时的执行实例 ID（手动/定时触发为 null）", example = "1234567890123456789")
    private Long dagExecutionId;
    /** DAG 编排触发时的 dag.id（用于前端跳转） */
    @Schema(description = "DAG 编排触发时的 DAG ID（用于前端跳转）", example = "1234567890123456789")
    private Long dagId;
    /** DAG 编排触发时的 DAG 名称（用于展示） */
    @Schema(description = "DAG 编排触发时的 DAG 名称（用于展示）")
    private String dagName;
    @Schema(description = "触发方式（MANUAL/CRON/DAG）")
    private String triggerType;
    @Schema(description = "执行状态（RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String status;
    @Schema(description = "开始时间（ISO 8601）")
    private LocalDateTime startTime;
    @Schema(description = "结束时间（ISO 8601）")
    private LocalDateTime endTime;
    @Schema(description = "耗时（毫秒）")
    private Long durationMs;
    @Schema(description = "耗时（秒）")
    private Long durationSeconds;
    @Schema(description = "吞吐（行/秒）")
    private Double throughputRowsPerSecond;
    @Schema(description = "源端读取行数")
    private Long sourceRows;
    @Schema(description = "目标端写入行数")
    private Long targetRows;
    @Schema(description = "错误信息")
    private String errorMessage;
    @Schema(description = "源数据库名")
    private String sourceDatabase;
    @Schema(description = "源 Schema 名")
    private String sourceSchema;
    @Schema(description = "源表名")
    private String sourceTable;
    @Schema(description = "目标库名")
    private String targetDatabase;
    @Schema(description = "目标表名")
    private String targetTable;
    /** 多表同步全量源表列表（单表时为单元素列表） */
    @Schema(description = "多表同步全量源表列表（单表时为单元素列表）")
    private List<String> sourceTables;
    /** 多表同步 per-table 明细（源表→目标表、状态、行数、耗时、错误） */
    @Schema(description = "多表同步 per-table 明细（源表→目标表、状态、行数、耗时、错误）")
    private List<SyncTableResultDTO> tableResults;
    @Schema(description = "同步模式（FULL/INCREMENTAL）")
    private String syncMode;
    @Schema(description = "增量字段名")
    private String incrementalField;
    @Schema(description = "父执行历史 ID（重试场景指向原始执行）", example = "1234567890123456789")
    private Long parentHistoryId;
    @Schema(description = "已重试次数")
    private Integer retryCount;
    @Schema(description = "下次重试时间（ISO 8601）")
    private LocalDateTime nextRetryAt;
    @Schema(description = "创建时间（ISO 8601）")
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

    public Long getDagExecutionId() {
        return dagExecutionId;
    }

    public void setDagExecutionId(Long dagExecutionId) {
        this.dagExecutionId = dagExecutionId;
    }

    public Long getDagId() {
        return dagId;
    }

    public void setDagId(Long dagId) {
        this.dagId = dagId;
    }

    public String getDagName() {
        return dagName;
    }

    public void setDagName(String dagName) {
        this.dagName = dagName;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
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

    public List<String> getSourceTables() {
        return sourceTables;
    }

    public void setSourceTables(List<String> sourceTables) {
        this.sourceTables = sourceTables;
    }

    public List<SyncTableResultDTO> getTableResults() {
        return tableResults;
    }

    public void setTableResults(List<SyncTableResultDTO> tableResults) {
        this.tableResults = tableResults;
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

    public Long getParentHistoryId() {
        return parentHistoryId;
    }

    public void setParentHistoryId(Long parentHistoryId) {
        this.parentHistoryId = parentHistoryId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
