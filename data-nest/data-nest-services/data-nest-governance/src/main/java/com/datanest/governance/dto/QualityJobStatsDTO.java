package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量任务配置统计（列表页顶部统计卡）")
@Data
public class QualityJobStatsDTO {

    @Schema(description = "已启用任务数")
    private Long enabled;

    @Schema(description = "已停用任务数")
    private Long disabled;

    @Schema(description = "定时调度任务数")
    private Long scheduled;

    @Schema(description = "自动触发任务数")
    private Long auto;
}
