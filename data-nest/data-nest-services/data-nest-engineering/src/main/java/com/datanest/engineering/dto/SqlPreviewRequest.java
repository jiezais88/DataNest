package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(description = "SQL 试运行请求（DAG 编辑器「运行测试」预执行校验，不触发元数据登记）")
public class SqlPreviewRequest {

    @Schema(description = "待执行 SQL（支持多语句）")
    private String sql;
    @Schema(description = "数据源 ID（为空时回退内置 Doris）", example = "1234567890123456789")
    private Long datasourceId;
    /** Sprint 4：DAG 参数草稿（未保存 DAG 时也能替换 ${param}） */
    @Schema(description = "DAG 参数草稿（未保存 DAG 时也能替换 ${param} 占位符）")
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
