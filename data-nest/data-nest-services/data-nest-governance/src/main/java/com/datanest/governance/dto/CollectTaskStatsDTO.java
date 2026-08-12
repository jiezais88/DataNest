package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "采集任务统计（列表页顶部统计卡）")
@Data
public class CollectTaskStatsDTO {

    @Schema(description = "运行中任务数")
    private Long running;

    @Schema(description = "成功任务数")
    private Long success;

    @Schema(description = "失败任务数")
    private Long failed;

    @Schema(description = "未执行任务数")
    private Long never;
}
