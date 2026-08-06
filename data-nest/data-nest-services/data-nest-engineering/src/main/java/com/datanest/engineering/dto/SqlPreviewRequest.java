package com.datanest.engineering.dto;

import java.util.Map;

/**
 * SQL preview request body.
 * <p>
 * Sprint 3: used for "Run Test" pre-execution validation in DAG editor.
 * Does NOT trigger metadata registration (differs from formal callback path).
 * <p>
 * datasourceId optional: if null, falls back to built-in Doris
 * (same behavior as DagNodeCallback path).
 * <p>
 * Sprint 4: params optional, used for placeholder replacement when DAG is not saved yet.
 */
public class SqlPreviewRequest {

    private String sql;
    private Long datasourceId;
    /** Sprint 4：DAG 参数草稿（未保存 DAG 时也能替换 ${param}） */
    private Map<String, Object> params;

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

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
