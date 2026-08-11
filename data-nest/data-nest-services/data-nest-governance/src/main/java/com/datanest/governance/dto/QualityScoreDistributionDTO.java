package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "表评分分布（当前最新评分按健康度计数 + 无评分表数，质量报告环图用）")
@Data
public class QualityScoreDistributionDTO {

    @Schema(description = "优秀（EXCELLENT）表数")
    private Long excellentCount;

    @Schema(description = "良好（GOOD）表数")
    private Long goodCount;

    @Schema(description = "一般（WARNING）表数")
    private Long warningCount;

    @Schema(description = "差（BAD）表数")
    private Long badCount;

    @Schema(description = "无评分表数（范围内 ONLINE 但无评分记录）")
    private Long noScoreCount;

    @Schema(description = "范围内 ONLINE 表总数")
    private Long totalTables;
}
