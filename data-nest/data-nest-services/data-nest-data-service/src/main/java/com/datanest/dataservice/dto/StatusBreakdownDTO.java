package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 状态码三档汇总（Sprint 10 F3，单 API 详情「错误码分布」2xx/4xx/5xx 占比）。
 */
@Data
@Schema(description = "状态码三档汇总")
public class StatusBreakdownDTO {

    @Schema(description = "2xx 成功数")
    private Long success;

    @Schema(description = "4xx 客户端错误数")
    private Long clientError;

    @Schema(description = "5xx 服务端错误数")
    private Long serverError;
}
