package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "采集执行历史统计（列表页顶部统计卡，按时间范围聚合）")
@Data
public class CollectHistoryStatsDTO {

    @Schema(description = "运行中执行数")
    private Long running;

    @Schema(description = "成功执行数")
    private Long success;

    @Schema(description = "失败执行数")
    private Long failed;

    @Schema(description = "已终止执行数")
    private Long terminated;
}
