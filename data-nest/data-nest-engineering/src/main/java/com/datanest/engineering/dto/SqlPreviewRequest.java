package com.datanest.engineering.dto;

/**
 * SQL preview request body.
 * <p>
 * Sprint 3: used for "Run Test" pre-execution validation in DAG editor.
 * Does NOT trigger metadata registration (differs from formal callback path).
 * <p>
 * datasourceId optional: if null, falls back to built-in Doris
 * (same behavior as DagNodeCallback path).
 */
public class SqlPreviewRequest {

    private String sql;
    private Long datasourceId;

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }
}
