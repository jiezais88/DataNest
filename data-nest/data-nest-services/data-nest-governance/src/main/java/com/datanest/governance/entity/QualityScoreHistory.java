package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表评分快照历史（Sprint 8 F3 DG-07）。
 * <p>
 * 每次检查批次结束由 ScoreCalculator 写一条该表评分快照（与 quality_score 同一次计算结果），
 * 评分趋势图读本表，避免每次报告现算。存量首快照经 POST /quality/report/backfill-score-history 补写。
 */
@Data
@TableName("quality_score_history")
public class QualityScoreHistory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 目标表 metadata_table.id */
    private Long tableId;

    /** 库名.表名快照 */
    private String tableName;

    /** 数据源（-1 = 内置 Doris） */
    private Long datasourceId;

    /** 0-100 评分 */
    private BigDecimal score;

    /** 健康度：EXCELLENT/GOOD/WARNING/BAD */
    private String healthLevel;

    /** 通过规则数 */
    private Integer passRules;

    /** 警告规则数 */
    private Integer warningRules;

    /** 严重规则数（UNAVAILABLE 不计入三档） */
    private Integer severeRules;

    /** 检查批次结束时间（趋势图 X 轴） */
    private LocalDateTime checkedAt;

    /** 记录创建时间 */
    private LocalDateTime createdAt;
}
