package com.datanest.engineering.dto;

import com.datanest.task.core.dto.FieldMappingItem;
import com.datanest.task.core.dto.SourceTableDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "批量数据同步任务 DTO")
public class SyncJobDTO {

    @Schema(description = "同步任务 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "任务名称")
    private String name;
    @Schema(description = "源数据源 ID", example = "1234567890123456789")
    private Long sourceDatasourceId;
    @Schema(description = "源数据库名")
    private String sourceDatabase;
    @Schema(description = "源 Schema 名")
    private String sourceSchema;
    @Schema(description = "源数据表列表")
    private List<String> sourceTables;
    @Schema(description = "同步模式（FULL/INCREMENTAL）")
    private String syncMode;
    @Schema(description = "增量字段名")
    private String incrementalField;
    @Schema(description = "触发方式（MANUAL/CRON）")
    private String triggerType;
    @Schema(description = "Cron 表达式")
    private String cronExpression;
    @Schema(description = "重试次数")
    private Integer retryTimes;
    @Schema(description = "重试间隔（分钟）")
    private Integer retryInterval;
    @Schema(description = "字段映射配置")
    private List<FieldMappingItem> fieldMapping;
    @Schema(description = "调度状态（NORMAL/PAUSED）")
    private String status;
    @Schema(description = "执行状态（PENDING/RUNNING/SUCCESS/FAILED/TERMINATED）")
    private String executionStatus;
    @Schema(description = "目标库名")
    private String targetDatabase;
    @Schema(description = "目标表名")
    private String targetTable;
    @Schema(description = "多表源表明细（含增量字段、上次同步时间）")
    private List<SourceTableDetail> sourceTablesDetail;
    @Schema(description = "读取速率限制（MB/s，0=不限制）")
    private Integer readRateLimitMbps;
    @Schema(description = "写入速率限制（行/秒，0=不限制）")
    private Integer writeRateLimitRowsPerSecond;
    @Schema(description = "限流总开关")
    private Boolean rateLimitEnabled;
    @Schema(description = "下次执行时间（ISO 8601）")
    private LocalDateTime nextExecutionTime;
    @Schema(description = "调度是否启用")
    private Boolean scheduleEnabled;
    @Schema(description = "PowerJob 调度任务 ID", example = "1234567890123456789")
    private Long schedulerJobId;
    @Schema(description = "任务描述")
    private String description;
    @Schema(description = "最近执行时间（ISO 8601）")
    private LocalDateTime lastExecuteTime;
    @Schema(description = "最近一次执行历史 ID", example = "1234567890123456789")
    private Long lastHistoryId;
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

    public Long getSourceDatasourceId() {
        return sourceDatasourceId;
    }

    public void setSourceDatasourceId(Long sourceDatasourceId) {
        this.sourceDatasourceId = sourceDatasourceId;
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

    public List<String> getSourceTables() {
        return sourceTables;
    }

    public void setSourceTables(List<String> sourceTables) {
        this.sourceTables = sourceTables;
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

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Integer getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(Integer retryTimes) {
        this.retryTimes = retryTimes;
    }

    public Integer getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Integer retryInterval) {
        this.retryInterval = retryInterval;
    }

    public List<FieldMappingItem> getFieldMapping() {
        return fieldMapping;
    }

    public void setFieldMapping(List<FieldMappingItem> fieldMapping) {
        this.fieldMapping = fieldMapping;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
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

    public List<SourceTableDetail> getSourceTablesDetail() {
        return sourceTablesDetail;
    }

    public void setSourceTablesDetail(List<SourceTableDetail> sourceTablesDetail) {
        this.sourceTablesDetail = sourceTablesDetail;
    }

    public Integer getReadRateLimitMbps() {
        return readRateLimitMbps;
    }

    public void setReadRateLimitMbps(Integer readRateLimitMbps) {
        this.readRateLimitMbps = readRateLimitMbps;
    }

    public Integer getWriteRateLimitRowsPerSecond() {
        return writeRateLimitRowsPerSecond;
    }

    public void setWriteRateLimitRowsPerSecond(Integer writeRateLimitRowsPerSecond) {
        this.writeRateLimitRowsPerSecond = writeRateLimitRowsPerSecond;
    }

    public Boolean getRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(Boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public LocalDateTime getNextExecutionTime() {
        return nextExecutionTime;
    }

    public void setNextExecutionTime(LocalDateTime nextExecutionTime) {
        this.nextExecutionTime = nextExecutionTime;
    }

    public Boolean getScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(Boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public Long getSchedulerJobId() {
        return schedulerJobId;
    }

    public void setSchedulerJobId(Long schedulerJobId) {
        this.schedulerJobId = schedulerJobId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getLastExecuteTime() {
        return lastExecuteTime;
    }

    public void setLastExecuteTime(LocalDateTime lastExecuteTime) {
        this.lastExecuteTime = lastExecuteTime;
    }

    public Long getLastHistoryId() {
        return lastHistoryId;
    }

    public void setLastHistoryId(Long lastHistoryId) {
        this.lastHistoryId = lastHistoryId;
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
