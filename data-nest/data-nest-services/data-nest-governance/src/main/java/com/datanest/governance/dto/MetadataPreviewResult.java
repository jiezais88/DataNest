package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "元数据预览结果")
public class MetadataPreviewResult {

    @Schema(description = "列名列表")
    private List<String> columns;
    @Schema(description = "数据行（按列名组织的键值对）")
    private List<Map<String, Object>> rows;
    @Schema(description = "本次返回行数")
    private int rowCount;
    @Schema(description = "总行数")
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
