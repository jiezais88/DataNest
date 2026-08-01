package com.datanest.engineering.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL preview response: independent result per statement.
 * <p>
 * Each statement has status (SUCCESS/FAILED) and type (QUERY/DML/DDL).
 * QUERY additionally returns columns + rows (max 200 rows to avoid heavy payloads).
 * DDL only returns message. DML returns rowCount.
 */
public class SqlPreviewResponse {

    private List<StatementResult> statements = new ArrayList<>();

    public List<StatementResult> getStatements() {
        return statements;
    }

    public void setStatements(List<StatementResult> statements) {
        this.statements = statements;
    }

    public static class StatementResult {
        private String stmt;
        private String status;
        private String type;
        private int rowCount;
        private List<String> columns;
        private List<List<Object>> rows;
        private String message;
        private String error;

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
    }
}
