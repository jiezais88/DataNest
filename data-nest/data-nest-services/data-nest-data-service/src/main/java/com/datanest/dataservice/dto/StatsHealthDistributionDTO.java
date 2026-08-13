package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * API 健康分布（Sprint 10 F3，对齐告警 PASS/WARNING/SEVERE 语义）。
 */
@Data
@Schema(description = "API 健康分布")
public class StatsHealthDistributionDTO {

    @Schema(description = "平台综合健康分（0~100）")
    private Integer overallScore;

    @Schema(description = "健康 API 数")
    private Integer healthyCount;

    @Schema(description = "警告 API 数")
    private Integer warningCount;

    @Schema(description = "严重 API 数")
    private Integer severeCount;

    @Schema(description = "各 API 健康明细")
    private List<StatsHealthItemDTO> items;
}
