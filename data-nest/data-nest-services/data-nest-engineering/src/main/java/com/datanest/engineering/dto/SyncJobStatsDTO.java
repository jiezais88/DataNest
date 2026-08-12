package com.datanest.engineering.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "同步任务状态统计（列表页顶部统计卡，与列表筛选项一一对应）")
@Data
public class SyncJobStatsDTO {

    @Schema(description = "运行中任务数")
    private Long running;

    @Schema(description = "成功任务数")
    private Long success;

    @Schema(description = "失败任务数")
    private Long failed;

    @Schema(description = "已终止任务数")
    private Long terminated;

    @Schema(description = "待执行任务数（PENDING）")
    private Long pending;
}
