package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "表评分趋势点")
@Data
public class QualityScoreTrendPointDTO {

    @Schema(description = "检查批次结束时间（ISO 8601）")
    private LocalDateTime checkedAt;

    @Schema(description = "0-100 评分")
    private BigDecimal score;

    @Schema(description = "健康度（EXCELLENT/GOOD/WARNING/BAD）")
    private String healthLevel;
}
