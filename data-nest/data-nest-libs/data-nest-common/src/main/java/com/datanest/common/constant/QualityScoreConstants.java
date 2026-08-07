package com.datanest.common.constant;

import java.math.BigDecimal;

/**
 * 表级质量评分常量（Sprint 6 NG8）。
 * <p>
 * 健康度四档区间为代码常量（2026-08-05 调研业界主流做法对齐）：
 * EXCELLENT ≥ 85；GOOD 75~84；WARNING 60~74；BAD &lt; 60。
 * 仅扣分分值与低分区阈值（bad-threshold）随 Nacos 配置调整（见 {@code ScoreCalculator}）。
 */
public final class QualityScoreConstants {

    private QualityScoreConstants() {
    }

    /** 健康度：优秀 */
    public static final String HEALTH_EXCELLENT = "EXCELLENT";

    /** 健康度：良好 */
    public static final String HEALTH_GOOD = "GOOD";

    /** 健康度：一般 */
    public static final String HEALTH_WARNING = "WARNING";

    /** 健康度：差 */
    public static final String HEALTH_BAD = "BAD";

    /** EXCELLENT 分数下限（≥ 此值） */
    public static final BigDecimal SCORE_EXCELLENT = new BigDecimal("85");

    /** GOOD 分数下限（≥ 此值） */
    public static final BigDecimal SCORE_GOOD = new BigDecimal("75");

    /** WARNING 分数下限（≥ 此值；< 此值 → BAD） */
    public static final BigDecimal SCORE_WARNING = new BigDecimal("60");

    /** 满分为 100 */
    public static final BigDecimal SCORE_MAX = new BigDecimal("100");
}
