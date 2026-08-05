package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.constant.QualityScoreConstants;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.QualityCheckDetail;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityScore;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.QualityCheckDetailMapper;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityScoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 表级质量评分计算（Sprint 6 NG8）。
 * <p>
 * 在质量检查批次收尾后调用 {@link #recalculateForTables(List)}，对本次涉及的每张表
 * **跨任务聚合**该表所有启用规则的最近一次检查结果（按 rule_id 取最近），按 PRD §6.5.1 加权算法产出 0-100 分并 upsert
 * {@code quality_score}，一张表一行。
 * <p>
 * 算法：
 * <ul>
 *   <li>基础分 = 100 × (PASS 规则权重之和 / 有效启用规则权重之和)</li>
 *   <li>总扣分 = Σ(WARNING 规则权重) × warning-deduct + Σ(SEVERE 规则权重) × severe-deduct</li>
 *   <li>最终分 = max(0, 基础分 − 总扣分)，保留 2 位小数</li>
 *   <li>健康度：EXCELLENT ≥85 / GOOD 75~84 / WARNING 60~74 / BAD &lt;60；存在 SEVERE 规则强制 BAD 并压入低分区</li>
 *   <li>UNAVAILABLE 规则不参与：不计入通过、不扣分、权重从分母剔除（与告警语义一致）</li>
 * </ul>
 */
@Service
public class ScoreCalculator {

    private static final Logger logger = LoggerFactory.getLogger(ScoreCalculator.class);

    private final QualityRuleMapper ruleMapper;
    private final QualityCheckDetailMapper detailMapper;
    private final QualityScoreMapper scoreMapper;
    private final MetadataTableMapper tableMapper;

    @Value("${datanest.quality.score.warning-deduct:10}")
    private int warningDeduct;

    @Value("${datanest.quality.score.severe-deduct:30}")
    private int severeDeduct;

    @Value("${datanest.quality.score.bad-threshold:60}")
    private int badThreshold;

    public ScoreCalculator(QualityRuleMapper ruleMapper,
                           QualityCheckDetailMapper detailMapper,
                           QualityScoreMapper scoreMapper,
                           MetadataTableMapper tableMapper) {
        this.ruleMapper = ruleMapper;
        this.detailMapper = detailMapper;
        this.scoreMapper = scoreMapper;
        this.tableMapper = tableMapper;
    }

    /**
     * 批量重算指定表的评分并 upsert。每张表跨任务聚合其所有启用规则的最近一次结果。
     */
    public void recalculateForTables(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return;
        }
        for (Long tableId : tableIds.stream().distinct().toList()) {
            try {
                recalculateForTable(tableId);
            } catch (Exception e) {
                logger.warn("表级评分计算失败: tableId={}, error={}", tableId, e.getMessage());
            }
        }
    }

    /**
     * 单表评分重算：查该表所有启用规则 → 逐条取最近一次结果 → 加权算分 → upsert。
     * 无有效启用规则（或全部 UNAVAILABLE）时删除/不落行（血缘显示灰色「—」）。
     */
    private void recalculateForTable(Long tableId) {
        MetadataTable table = tableMapper.selectById(tableId);
        if (table == null) {
            return;
        }
        List<QualityRule> rules = ruleMapper.selectList(new QueryWrapper<QualityRule>()
                .eq("table_id", tableId)
                .eq("enabled", 1));

        int passRules = 0;
        int warningRules = 0;
        int severeRules = 0;
        BigDecimal passWeight = BigDecimal.ZERO;
        BigDecimal warningWeight = BigDecimal.ZERO;
        BigDecimal severeWeight = BigDecimal.ZERO;
        BigDecimal validWeight = BigDecimal.ZERO;

        for (QualityRule rule : rules) {
            String level = latestLevel(rule.getId());
            BigDecimal weight = weight(rule);
            // UNAVAILABLE / 无结果：不参与，权重从分母剔除
            if (level == null || AlertConstants.QUALITY_LEVEL_UNAVAILABLE.equals(level)) {
                continue;
            }
            validWeight = validWeight.add(weight);
            if (AlertConstants.QUALITY_LEVEL_PASS.equals(level)) {
                passRules++;
                passWeight = passWeight.add(weight);
            } else if (AlertConstants.QUALITY_LEVEL_WARNING.equals(level)) {
                warningRules++;
                warningWeight = warningWeight.add(weight);
            } else if (AlertConstants.QUALITY_LEVEL_SEVERE.equals(level)) {
                severeRules++;
                severeWeight = severeWeight.add(weight);
            }
        }

        // 无有效规则（未配置或无最近结果）→ 不落行
        if (validWeight.compareTo(BigDecimal.ZERO) <= 0) {
            removeScore(tableId);
            return;
        }

        // 基础分 = 100 × (通过权重 / 有效权重)
        BigDecimal baseScore = QualityScoreConstants.SCORE_MAX
                .multiply(passWeight).divide(validWeight, 6, RoundingMode.HALF_UP);
        // 总扣分 = Σ警告权重×warningDeduct + Σ严重权重×severeDeduct
        BigDecimal deduction = warningWeight.multiply(BigDecimal.valueOf(warningDeduct))
                .add(severeWeight.multiply(BigDecimal.valueOf(severeDeduct)));
        BigDecimal finalScore = baseScore.subtract(deduction).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        // 存在严重规则 → 强制压入低分区并标记 BAD（PRD：有严重强制健康度「差」）
        String healthLevel;
        if (severeRules > 0) {
            finalScore = finalScore.min(BigDecimal.valueOf(badThreshold)
                    .subtract(new BigDecimal("0.01")));
            healthLevel = QualityScoreConstants.HEALTH_BAD;
        } else {
            healthLevel = determineHealth(finalScore);
        }

        upsert(table, finalScore, healthLevel, passRules, warningRules, severeRules);
    }

    /** 取某规则最近一次检查的分级（按 id 倒序取最新一条），无记录返回 null。 */
    private String latestLevel(Long ruleId) {
        List<QualityCheckDetail> details = detailMapper.selectList(new QueryWrapper<QualityCheckDetail>()
                .eq("rule_id", ruleId)
                .orderByDesc("id")
                .last("limit 1"));
        if (details == null || details.isEmpty()) {
            return null;
        }
        return details.get(0).getResultLevel();
    }

    /** 规则权重，空/非正按 1 处理。 */
    private BigDecimal weight(QualityRule rule) {
        Integer w = rule.getWeight();
        if (w == null || w <= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(w);
    }

    /** 按分数区间映射健康度四档。 */
    private String determineHealth(BigDecimal score) {
        if (score.compareTo(QualityScoreConstants.SCORE_EXCELLENT) >= 0) {
            return QualityScoreConstants.HEALTH_EXCELLENT;
        }
        if (score.compareTo(QualityScoreConstants.SCORE_GOOD) >= 0) {
            return QualityScoreConstants.HEALTH_GOOD;
        }
        if (score.compareTo(QualityScoreConstants.SCORE_WARNING) >= 0) {
            return QualityScoreConstants.HEALTH_WARNING;
        }
        return QualityScoreConstants.HEALTH_BAD;
    }

    /** upsert：按 table_id 查存在则更新，否则插入。 */
    private void upsert(MetadataTable table, BigDecimal score, String healthLevel,
                        int passRules, int warningRules, int severeRules) {
        LocalDateTime now = LocalDateTime.now();
        QualityScore existing = scoreMapper.selectOne(new QueryWrapper<QualityScore>()
                .eq("table_id", table.getId()).last("limit 1"));
        if (existing != null) {
            existing.setScore(score);
            existing.setHealthLevel(healthLevel);
            existing.setPassRules(passRules);
            existing.setWarningRules(warningRules);
            existing.setSevereRules(severeRules);
            existing.setLastCheckedAt(now);
            existing.setUpdatedAt(now);
            scoreMapper.updateById(existing);
        } else {
            QualityScore s = new QualityScore();
            s.setTableId(table.getId());
            s.setTableName(qualifiedTableName(table));
            s.setDatasourceId(table.getDatasourceId());
            s.setScore(score);
            s.setHealthLevel(healthLevel);
            s.setPassRules(passRules);
            s.setWarningRules(warningRules);
            s.setSevereRules(severeRules);
            s.setLastCheckedAt(now);
            s.setUpdatedAt(now);
            scoreMapper.insert(s);
        }
    }

    /** 无有效规则时删除历史评分，避免展示过期数据。 */
    private void removeScore(Long tableId) {
        QualityScore existing = scoreMapper.selectOne(new QueryWrapper<QualityScore>()
                .eq("table_id", tableId).last("limit 1"));
        if (existing != null) {
            scoreMapper.deleteById(existing.getId());
        }
    }

    /** 组装库名.表名（schema 存在时优先用 schema）。 */
    private String qualifiedTableName(MetadataTable table) {
        String schema = table.getSchemaName();
        String tableName = table.getTableName();
        String qualified = tableName;
        if (schema != null && !schema.isBlank()) {
            qualified = schema + "." + tableName;
        } else if (table.getDatabaseName() != null && !table.getDatabaseName().isBlank()) {
            qualified = table.getDatabaseName() + "." + tableName;
        }
        return qualified;
    }
}
