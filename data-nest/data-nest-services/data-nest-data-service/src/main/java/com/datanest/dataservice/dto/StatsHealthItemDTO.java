package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单 API 健康分级明细（Sprint 10 F3）。
 */
@Data
@Schema(description = "单 API 健康分级")
public class StatsHealthItemDTO {

    @Schema(description = "API ID")
    private Long apiId;

    @Schema(description = "API 名称")
    private String name;

    @Schema(description = "对外路径")
    private String path;

    @Schema(description = "健康级别：PASS 健康 / WARNING 警告 / SEVERE 严重")
    private String level;

    @Schema(description = "调用量")
    private Long totalCalls;

    @Schema(description = "错误率（0~1，非 2xx 占比）")
    private Double errorRate;

    @Schema(description = "P95 耗时毫秒")
    private Double p95Ms;

    @Schema(description = "限流命中比例（0~1）")
    private Double rateLimitRatio;
}
