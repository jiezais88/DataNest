package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "质量报告 KPI 汇总")
@Data
public class QualityReportSummaryDTO {

    @Schema(description = "检查批次数（范围内有明细的 distinct 批次）")
    private Long batchCount;

    @Schema(description = "规则明细总数")
    private Long detailCount;

    @Schema(description = "平均评分（范围内所有表当前最新评分均值，与时间/任务无关；无评分表为 null）")
    private BigDecimal avgScore;

    @Schema(description = "通过率（PASS 明细数 / 有效明细数，排除 UNAVAILABLE；0-100，无有效明细为 null）")
    private BigDecimal passRate;

    @Schema(description = "待处理问题：SEVERE 明细数")
    private Long severeCount;

    @Schema(description = "待处理问题：WARNING 明细数")
    private Long warningCount;
}
