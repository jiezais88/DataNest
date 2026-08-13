package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 错误码分布项（Sprint 10 F3，4xx/5xx TopN）。
 */
@Data
@Schema(description = "错误码分布项")
public class StatsErrorCodeDTO {

    @Schema(description = "HTTP 状态码")
    private Integer statusCode;

    @Schema(description = "该状态码出现次数")
    private Long count;

    @Schema(description = "占错误总量比例（0~1）")
    private Double ratio;

    @Schema(description = "429 限流命中最多的 API 名（仅 429 条目返回；null = 无 429 或 API 已删除）")
    private String top429ApiName;
}
