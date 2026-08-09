package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL preview response: independent result per statement.
 * <p>
 * Each statement has status (SUCCESS/FAILED) and type (QUERY/DML/DDL).
 * QUERY additionally returns columns + rows (max 200 rows to avoid heavy payloads).
 * DDL only returns message. DML returns rowCount.
 */
@Schema(description = "SQL 试运行响应（每条语句独立返回结果）")
public class SqlPreviewResponse {

    @Schema(description = "各语句执行结果列表")
    private List<StatementResult> statements = new ArrayList<>();

    public List<StatementResult> getStatements() {
        return statements;
    }

    public void setStatements(List<StatementResult> statements) {
        this.statements = statements;
    }

    @Schema(description = "单条语句执行结果")
    public static class StatementResult {
        @Schema(description = "SQL 语句原文")
        private String stmt;
        @Schema(description = "执行状态（SUCCESS/FAILED）")
        private String status;
        @Schema(description = "语句类型（QUERY/DML/DDL）")
        private String type;
        @Schema(description = "影响行数（DML）")
        private int rowCount;
        @Schema(description = "结果集列名（QUERY）")
        private List<String> columns;
        @Schema(description = "结果集数据行（QUERY，最多 200 行）")
        private List<List<Object>> rows;
        @Schema(description = "提示信息（DDL）")
        private String message;
        @Schema(description = "错误信息")
        private String error;
        /** 该语句执行耗时（毫秒），含失败语句 */
        @Schema(description = "该语句执行耗时（毫秒），含失败语句")
        private Long durationMs;
        /** QUERY 结果集是否被行数上限截断（见 GenericSqlExecutor.PREVIEW_MAX_ROWS） */
        @Schema(description = "QUERY 结果集是否被行数上限截断")
        private boolean truncated;

        public String getStmt() {
            return stmt;
        }

        public void setStmt(String stmt) {
            this.stmt = stmt;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        public List<String> getColumns() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public List<List<Object>> getRows() {
            return rows;
        }

        public void setRows(List<List<Object>> rows) {
            this.rows = rows;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public Long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(Long durationMs) {
            this.durationMs = durationMs;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean truncated) {
            this.truncated = truncated;
        }
    }
}
