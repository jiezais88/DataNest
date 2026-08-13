package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 单 API 调用统计（Sprint 10 F3，API 详情页）。
 */
@Data
@Schema(description = "单 API 调用统计")
public class ApiStatsDTO {

    @Schema(description = "总调用量")
    private Long totalCalls;

    @Schema(description = "成功率（0~1）")
    private Double successRate;

    @Schema(description = "平均耗时毫秒")
    private Double avgMs;

    @Schema(description = "P95 耗时毫秒")
    private Double p95Ms;

    @Schema(description = "今日调用量")
    private Long todayCalls;

    @Schema(description = "调用量趋势（双线：调用量 + 失败数）")
    private List<TrendAgg> trend;

    @Schema(description = "最近调用明细")
    private List<ApiCallLogItemDTO> recentLogs;
}
