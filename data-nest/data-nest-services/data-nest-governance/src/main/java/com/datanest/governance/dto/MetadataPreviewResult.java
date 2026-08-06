package com.datanest.governance.dto;

import java.util.List;
import java.util.Map;

public class MetadataPreviewResult {

    private List<String> columns;
    private List<Map<String, Object>> rows;
    private int rowCount;
    private long totalRowCount;

    public MetadataPreviewResult() {
    }

    public MetadataPreviewResult(List<String> columns, List<Map<String, Object>> rows, int rowCount, long totalRowCount) {
        this.columns = columns;
        this.rows = rows;
        this.rowCount = rowCount;
        this.totalRowCount = totalRowCount;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
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
