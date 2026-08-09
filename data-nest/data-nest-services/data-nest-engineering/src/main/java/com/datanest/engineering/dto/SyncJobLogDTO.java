package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "同步任务执行日志行 DTO")
public class SyncJobLogDTO {

    @Schema(description = "日志 ID", example = "1234567890123456789")
    private Long id;
    @Schema(description = "所属执行历史 ID", example = "1234567890123456789")
    private Long historyId;
    @Schema(description = "同步任务 ID", example = "1234567890123456789")
    private Long syncJobId;
    @Schema(description = "日志级别（如 INFO/ERROR）")
    private String level;
    @Schema(description = "日志内容")
    private String message;
    @Schema(description = "行号")
    private Integer lineNum;
    /** 所属表名；平台概要行为 null（归「概览」） */
    @Schema(description = "所属表名；平台概要行为 null（归「概览」）")
    private String tableName;
    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getHistoryId() {
        return historyId;
    }

    public void setHistoryId(Long historyId) {
        this.historyId = historyId;
    }

    public Long getSyncJobId() {
        return syncJobId;
    }

    public void setSyncJobId(Long syncJobId) {
        this.syncJobId = syncJobId;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getLineNum() {
        return lineNum;
    }

    public void setLineNum(Integer lineNum) {
        this.lineNum = lineNum;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
