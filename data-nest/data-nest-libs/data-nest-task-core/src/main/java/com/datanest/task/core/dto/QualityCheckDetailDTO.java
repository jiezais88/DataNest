package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "质量检查规则明细")
@Data
public class QualityCheckDetailDTO {

    @Schema(description = "明细 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "批次 ID", example = "1234567890123456789")
    private Long batchId;

    @Schema(description = "规则 ID", example = "1234567890123456789")
    private Long ruleId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON）")
    private String ruleType;

    @Schema(description = "目标表 ID", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "目标表名")
    private String tableName;

    @Schema(description = "结果指标名")
    private String resultMetric;

    @Schema(description = "结果值")
    private BigDecimal resultValue;

    @Schema(description = "分级判定（PASS/WARNING/SEVERE/UNAVAILABLE）")
    private String resultLevel;

    @Schema(description = "警告阈值（判定依据，经 ruleId 回填，便于展示为什么严重）")
    private BigDecimal warningThreshold;

    @Schema(description = "严重阈值（判定依据，经 ruleId 回填）")
    private BigDecimal severeThreshold;

    @Schema(description = "执行是否成功（1 成功，0 失败）")
    private Integer success;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "实际执行 SQL")
    private String executedSql;

    @Schema(description = "创建时间（ISO 8601）")
    private LocalDateTime createdAt;
}
