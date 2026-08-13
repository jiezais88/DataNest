package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 全局调用统计 KPI（Sprint 10 F3，API 运行统计页）。
 */
@Data
@Schema(description = "全局调用统计 KPI")
public class StatsOverviewDTO {

    @Schema(description = "总调用量")
    private Long totalCalls;

    @Schema(description = "平均成功率（0~1，目标 ≥ 0.99）")
    private Double successRate;

    @Schema(description = "P95 耗时毫秒")
    private Double p95Ms;

    @Schema(description = "限流命中数（429）")
    private Long rateLimitedCount;

    @Schema(description = "限流命中占调用比例（0~1）")
    private Double rateLimitRatio;
}
