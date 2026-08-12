package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据 API 汇总（Sprint 10 F2，API 管理列表页统计卡）。
 */
@Data
@Schema(description = "数据 API 汇总")
public class DataApiSummaryDTO {

    @Schema(description = "已发布 API 数")
    private Long publishedCount;

    @Schema(description = "待发布（CREATED）API 数")
    private Long createdCount;

    @Schema(description = "已下线 API 数")
    private Long disabledCount;

    @Schema(description = "近 7 天总调用量（含已软删 API 的历史调用）")
    private Long totalCalls7d;
}
