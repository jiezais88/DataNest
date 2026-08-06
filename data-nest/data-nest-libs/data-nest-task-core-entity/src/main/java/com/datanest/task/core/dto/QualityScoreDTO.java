package com.datanest.task.core.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表级质量评分 DTO（Sprint 6 NG8）。
 * <p>
 * 供单表评分、批量评分（血缘回填）、评分列表分页三处展示。
 */
@Data
public class QualityScoreDTO {

    private Long id;

    /** 目标表 metadata_table.id */
    private Long tableId;

    /** 库名.表名 */
    private String tableName;

    /** 数据源 ID */
    private Long datasourceId;

    /** 数据源名称（关联展示用） */
    private String datasourceName;

    /** 0-100 分 */
    private BigDecimal score;

    /** 健康度：EXCELLENT/GOOD/WARNING/BAD */
    private String healthLevel;

    /** 健康度显示名：优秀/良好/一般/差 */
    private String healthLevelLabel;

    /** 最近一次通过规则数 */
    private Integer passRules;

    /** 最近一次警告规则数 */
    private Integer warningRules;

    /** 最近一次严重规则数 */
    private Integer severeRules;

    /** 最近检查时间 */
    private LocalDateTime lastCheckedAt;
}
