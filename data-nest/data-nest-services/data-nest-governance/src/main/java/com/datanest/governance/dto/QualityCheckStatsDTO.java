package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量检查批次统计（列表页顶部统计卡，按时间范围聚合）")
@Data
public class QualityCheckStatsDTO {

    @Schema(description = "运行中批次数")
    private Long running;

    @Schema(description = "成功批次数")
    private Long success;

    @Schema(description = "部分失败批次数")
    private Long partial;

    @Schema(description = "失败批次数")
    private Long failed;
}
