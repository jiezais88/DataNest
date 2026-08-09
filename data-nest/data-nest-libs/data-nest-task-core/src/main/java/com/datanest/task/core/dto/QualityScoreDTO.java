package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表级质量评分 DTO（Sprint 6 NG8）。
 * <p>
 * 供单表评分、批量评分（血缘回填）、评分列表分页三处展示。
 */
@Schema(description = "表级质量评分")
@Data
public class QualityScoreDTO {

    @Schema(description = "评分记录 ID", example = "1234567890123456789")
    private Long id;

    @Schema(description = "目标表 ID（metadata_table.id）", example = "1234567890123456789")
    private Long tableId;

    @Schema(description = "库名.表名")
    private String tableName;

    @Schema(description = "数据源 ID", example = "1234567890123456789")
    private Long datasourceId;

    @Schema(description = "数据源名称（关联展示用）")
    private String datasourceName;

    @Schema(description = "质量评分（0-100 分）")
    private BigDecimal score;

    @Schema(description = "健康度（EXCELLENT/GOOD/WARNING/BAD）")
    private String healthLevel;

    @Schema(description = "健康度显示名（优秀/良好/一般/差）")
    private String healthLevelLabel;

    @Schema(description = "最近一次通过规则数")
    private Integer passRules;

    @Schema(description = "最近一次警告规则数")
    private Integer warningRules;

    @Schema(description = "最近一次严重规则数")
    private Integer severeRules;

    @Schema(description = "最近检查时间（ISO 8601）")
    private LocalDateTime lastCheckedAt;
}
