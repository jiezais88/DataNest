package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "四档分布趋势点（按天）")
@Data
public class QualityLevelTrendPointDTO {

    @Schema(description = "日期（YYYY-MM-DD）")
    private String day;

    @Schema(description = "PASS 明细数")
    private Long passCount;

    @Schema(description = "WARNING 明细数")
    private Long warningCount;

    @Schema(description = "SEVERE 明细数")
    private Long severeCount;

    @Schema(description = "UNAVAILABLE 明细数")
    private Long unavailableCount;
}
