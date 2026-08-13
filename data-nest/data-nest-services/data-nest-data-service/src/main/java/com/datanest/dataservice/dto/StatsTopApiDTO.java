package com.datanest.dataservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Top API 调用排行项（Sprint 10 F3）。
 */
@Data
@Schema(description = "Top API 调用排行项")
public class StatsTopApiDTO {

    @Schema(description = "API ID")
    private Long apiId;

    @Schema(description = "API 名称")
    private String name;

    @Schema(description = "对外路径")
    private String path;

    @Schema(description = "调用量")
    private Long calls;
}
