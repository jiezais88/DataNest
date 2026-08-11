package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "评分趋势点（聚合模式按天；单表模式按批次）")
@Data
public class QualityScoreTrendPointDTO {

    @Schema(description = "检查批次结束时间（ISO 8601；仅单表模式）")
    private LocalDateTime checkedAt;

    @Schema(description = "0-100 评分（仅单表模式）")
    private BigDecimal score;

    @Schema(description = "健康度（EXCELLENT/GOOD/WARNING/BAD；仅单表模式）")
    private String healthLevel;

    @Schema(description = "日期（yyyy-MM-dd；仅聚合模式）")
    private String day;

    @Schema(description = "当天平均评分（仅聚合模式）")
    private BigDecimal avgScore;

    @Schema(description = "当天有评分快照的表数（仅聚合模式）")
    private Long tableCount;
}
