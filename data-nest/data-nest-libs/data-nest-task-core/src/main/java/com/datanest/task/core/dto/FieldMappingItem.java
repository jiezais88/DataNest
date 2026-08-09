package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "字段映射项")
public class FieldMappingItem {

    @Schema(description = "源列名")
    private String sourceColumn;
    @Schema(description = "目标列名")
    private String targetColumn;
    @Schema(description = "目标列类型")
    private String targetType;

    public String getSourceColumn() {
        return sourceColumn;
    }

    public void setSourceColumn(String sourceColumn) {
        this.sourceColumn = sourceColumn;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public void setTargetColumn(String targetColumn) {
        this.targetColumn = targetColumn;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }
}
