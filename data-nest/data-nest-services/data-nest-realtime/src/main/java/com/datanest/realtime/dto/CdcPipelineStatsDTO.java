package com.datanest.realtime.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "CDC 管道统计（列表页顶部统计卡，2026-08-10 前端联调确认新增）")
@Data
public class CdcPipelineStatsDTO {

    @Schema(description = "运行中管道数")
    private Long running;

    @Schema(description = "已停止管道数")
    private Long stopped;

    @Schema(description = "异常管道数")
    private Long error;

    @Schema(description = "已同步表总数（全部管道的表级映射条数）")
    private Long syncedTables;
}
