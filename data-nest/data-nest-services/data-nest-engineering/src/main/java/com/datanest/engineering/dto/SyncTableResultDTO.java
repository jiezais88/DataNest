package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 多表同步 per-table 明细 DTO（对应 sync_job_history.table_results 中每一条）。
 */
@Schema(description = "多表同步单表明细（对应 sync_job_history.table_results 中每一条）")
public record SyncTableResultDTO(
        @Schema(description = "源表名") String sourceTable,
        @Schema(description = "目标表名") String targetTable,
        @Schema(description = "同步状态（SUCCESS/FAILED）") String status,
        @Schema(description = "读取行数") Long readRows,
        @Schema(description = "写入行数") Long writeRows,
        @Schema(description = "耗时（毫秒）") Long durationMs,
        @Schema(description = "错误信息") String errorMessage) {
}
