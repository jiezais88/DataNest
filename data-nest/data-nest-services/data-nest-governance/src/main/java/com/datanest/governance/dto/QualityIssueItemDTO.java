package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "质量问题清单项（SEVERE/WARNING 规则明细）")
@Data
public class QualityIssueItemDTO {

    @Schema(description = "明细 ID", example = "1234567890123456789")
    private Long detailId;

    @Schema(description = "表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "表名（库名.表名）")
    private String tableName;

    @Schema(description = "规则 ID", example = "1234567890123456789")
    private Long ruleId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String ruleType;

    @Schema(description = "结果指标名")
    private String resultMetric;

    @Schema(description = "执行结果值")
    private BigDecimal resultValue;

    @Schema(description = "阈值（WARNING 取警告阈值、SEVERE 取严重阈值；规则已删为 null）")
    private BigDecimal threshold;

    @Schema(description = "分级（WARNING/SEVERE）")
    private String resultLevel;

    @Schema(description = "检查时间（ISO 8601）")
    private LocalDateTime checkedAt;
}
