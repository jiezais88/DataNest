package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "数据源质量对比项（按数据源分组的平均评分）")
@Data
public class DatasourceScoreComparisonDTO {

    @Schema(description = "数据源 ID（-1 = 内置 Doris）", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据源名称")
    private String datasourceName;

    @Schema(description = "平均评分（0-100）")
    private BigDecimal avgScore;

    @Schema(description = "参与评分表数")
    private Long tableCount;
}
