package com.datanest.engineering.dto;

/**
 * 多表同步 per-table 明细 DTO（对应 sync_job_history.table_results 中每一条）。
 */
public record SyncTableResultDTO(String sourceTable, String targetTable, String status,
                                 Long readRows, Long writeRows, Long durationMs, String errorMessage) {
}
