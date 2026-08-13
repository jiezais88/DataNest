package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用明细行（Sprint 10 F3，单 API 最近调用）。
 */
@Data
@Schema(description = "调用明细行")
public class ApiCallLogItemDTO {

    @Schema(description = "调用方 Key 名称（Key 已删除时为空）")
    private String keyName;

    @Schema(description = "HTTP 状态码")
    private Integer statusCode;

    @Schema(description = "耗时毫秒")
    private Integer durationMs;

    @Schema(description = "调用时间")
    private LocalDateTime createdAt;
}
