package com.datanest.engineering.dto;

import java.util.List;

public class DataPreviewResult {

    private List<String> columns;
    private List<List<Object>> rows;
    private int rowCount;
    private long totalRowCount;

    public DataPreviewResult() {
    }

    public DataPreviewResult(List<String> columns, List<List<Object>> rows, int rowCount, long totalRowCount) {
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

    public List<List<Object>> getRows() {
        return rows;
    }

    public void setRows(List<List<Object>> rows) {
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
