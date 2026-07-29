package com.datanest.engineering.dto;

import java.util.List;
import java.util.Map;

public class DataPreviewResult {

    private List<String> columns;
    private Map<String, String> columnTypes;
    private List<Map<String, Object>> rows;
    private int rowCount;
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
