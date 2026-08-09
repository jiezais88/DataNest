package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 数据源表预览结果。
 * Sprint 4 下沉到 task-core，供 engineering / governance 共用。
 */
@Schema(description = "数据源表预览结果")
public class DataPreviewResult {

    @Schema(description = "列名列表")
    private List<String> columns;
    @Schema(description = "列类型映射（列名 → 类型）")
    private Map<String, String> columnTypes;
    @Schema(description = "数据行（列名 → 值）")
    private List<Map<String, Object>> rows;
    @Schema(description = "本次返回行数")
    private int rowCount;
    @Schema(description = "总行数")
    private long totalRowCount;

    public DataPreviewResult() {
    }

    public DataPreviewResult(List<String> columns, Map<String, String> columnTypes,
                             List<Map<String, Object>> rows, int rowCount, long totalRowCount) {
        this.columns = columns;
        this.columnTypes = columnTypes;
        this.rows = rows;
        this.rowCount = rowCount;
        this.totalRowCount = totalRowCount;
    }

    public DataPreviewResult(List<String> columns, List<Map<String, Object>> rows, int rowCount, long totalRowCount) {
        this(columns, null, rows, rowCount, totalRowCount);
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public Map<String, String> getColumnTypes() {
        return columnTypes;
    }

    public void setColumnTypes(Map<String, String> columnTypes) {
        this.columnTypes = columnTypes;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public long getTotalRowCount() {
        return totalRowCount;
    }

    public void setTotalRowCount(long totalRowCount) {
        this.totalRowCount = totalRowCount;
    }
}
