package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量报告统一筛选请求（数据源/库/质量任务/时间范围 + 评分趋势表 + 问题清单分页）")
@Data
public class QualityReportRequest {

    @Schema(description = "数据源 ID（-1 = 内置 Doris）", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "库名（随数据源联动）")
    private String databaseName;

    @Schema(description = "质量任务 ID", example = "1234567890123456789")
    private Long jobId;

    @Schema(description = "开始时间（ISO 8601，空默认最近 30 天）", example = "2026-07-12T00:00:00")
    private String startTime;

    @Schema(description = "结束时间（ISO 8601，空默认当前时间）", example = "2026-08-11T00:00:00")
    private String endTime;

    @Schema(description = "表 ID（评分趋势必填）", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "页码（问题清单用，从 1 开始）")
    private Integer page;

    @Schema(description = "每页条数（问题清单用）")
    private Integer pageSize;
}
