package com.datanest.engineering.dto;

import com.datanest.task.core.dto.FieldMappingItem;
import jakarta.validation.constraints.*;

import java.util.List;

public class SyncJobCreateRequest {

    @NotBlank(message = "同步任务名称不能为空")
    @Size(min = 3, max = 50, message = "同步任务名称长度需在 3-50 个字符之间")
    private String name;

    @NotNull(message = "源数据源 ID 不能为空")
    private Long sourceDatasourceId;

    @Size(max = 100, message = "源数据库名最多 100 个字符")
    private String sourceDatabase;

    @Size(max = 100, message = "源 Schema 名最多 100 个字符")
    private String sourceSchema;

    @NotEmpty(message = "源数据表不能为空")
    private List<String> sourceTables;

    @NotBlank(message = "同步模式不能为空")
    @Pattern(regexp = "^(FULL|INCREMENTAL)$", message = "同步模式只能是 FULL 或 INCREMENTAL")
    private String syncMode;

    @Size(max = 100, message = "增量字段名最多 100 个字符")
    private String incrementalField;

    @NotBlank(message = "触发方式不能为空")
    @Pattern(regexp = "^(MANUAL|CRON)$", message = "触发方式只能是 MANUAL 或 CRON")
    private String triggerType;

    @Size(max = 100, message = "Cron 表达式最多 100 个字符")
    private String cronExpression;

    @NotBlank(message = "目标库名不能为空")
    @Size(max = 100, message = "目标库名最多 100 个字符")
    private String targetDatabase;

    @NotBlank(message = "目标表名不能为空")
    @Size(max = 100, message = "目标表名最多 100 个字符")
    private String targetTable;

    @Min(value = 0, message = "重试次数不能小于 0")
    @Max(value = 3, message = "重试次数不能大于 3")
    private Integer retryTimes = 3;

    @Min(value = 0, message = "重试间隔不能小于 0")
    @Max(value = 30, message = "重试间隔不能大于 30")
    private Integer retryInterval = 5;

    private List<FieldMappingItem> fieldMapping;

    @Size(max = 1000, message = "描述最多 1000 个字符")
    private String description;

    @AssertTrue(message = "Cron 触发方式必须填写 Cron 表达式")
    public boolean isCronExpressionValid() {
        return !"CRON".equalsIgnoreCase(triggerType) || (cronExpression != null && !cronExpression.isBlank());
    }

    @AssertTrue(message = "增量同步必须填写增量字段")
    public boolean isIncrementalFieldValid() {
        return !"INCREMENTAL".equalsIgnoreCase(syncMode) || (incrementalField != null && !incrementalField.isBlank());
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

    public List<FieldMappingItem> getFieldMapping() {
        return fieldMapping;
    }

    public void setFieldMapping(List<FieldMappingItem> fieldMapping) {
        this.fieldMapping = fieldMapping;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
