package com.datanest.governance.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireBatchRequest;
import com.datanest.alert.api.dto.AlertItem;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.governance.api.dto.QualityBatchCreateRequest;
import com.datanest.governance.api.dto.QualityBatchFinishRequest;
import com.datanest.governance.api.dto.QualityBatchInfoDTO;
import com.datanest.governance.api.dto.QualityDetailCreateRequest;
import com.datanest.governance.api.dto.QualityExecutionPlanDTO;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.entity.QualityCheckBatch;
import com.datanest.governance.entity.QualityCheckDetail;
import com.datanest.governance.entity.QualityJob;
import com.datanest.governance.entity.QualityRule;
import com.datanest.governance.entity.QualityRuleTemplate;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.governance.mapper.QualityCheckBatchMapper;
import com.datanest.governance.mapper.QualityCheckDetailMapper;
import com.datanest.governance.mapper.QualityJobMapper;
import com.datanest.governance.mapper.QualityRuleMapper;
import com.datanest.governance.mapper.QualityRuleTemplateMapper;
import com.datanest.common.constant.AlertConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 质量执行域内部逻辑（微服务化 4.1，实现 governance-api 的 QualityExecutionApi 契约）。
 * <p>
 * 自 task-core {@code QualityCheckService} 搬运：执行计划装配（任务 + 启用规则 + 模板 + 元数据表 +
 * {@link RuleSqlGenerator} 生成执行 SQL）、批次/明细落库、批次收尾串联
 * （终态回写 + last_trigger_at + {@link ScoreCalculator} 评分重算 + 合并告警）。
 * SQL 实际执行与阈值判定（determineLevel）留在 worker 侧。
 * <p>
 * 参照源 {@code QualityCheckService} 渐进式落库语义：批次收尾不挂方法级事务，
 * 评分按表单表容错、告警经 RemoteCalls 降级，单点失败只记 warn 不影响整体收尾。
 */
@Service
public class QualityExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(QualityExecutionService.class);

    private final QualityCheckBatchMapper batchMapper;
    private final QualityCheckDetailMapper detailMapper;
    private final QualityJobMapper jobMapper;
    private final QualityRuleMapper ruleMapper;
    private final QualityRuleTemplateMapper templateMapper;
    private final MetadataTableMapper tableMapper;
    private final AlertApi alertApi;
    private final ScoreCalculator scoreCalculator;

    public QualityExecutionService(QualityCheckBatchMapper batchMapper,
                                   QualityCheckDetailMapper detailMapper,
                                   QualityJobMapper jobMapper,
                                   QualityRuleMapper ruleMapper,
                                   QualityRuleTemplateMapper templateMapper,
                                   MetadataTableMapper tableMapper,
                                   AlertApi alertApi,
                                   ScoreCalculator scoreCalculator) {
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
        this.jobMapper = jobMapper;
        this.ruleMapper = ruleMapper;
        this.templateMapper = templateMapper;
        this.tableMapper = tableMapper;
        this.alertApi = alertApi;
        this.scoreCalculator = scoreCalculator;
    }

    // ==================== 执行计划装配 ====================

    /**
     * 按任务装配执行计划：任务 + 启用规则（quality_job_rule 关联）+ 模板 + 元数据表 + 执行 SQL。
     * 规则空列表合法（对齐源 executeJob：无启用规则只记 warn，照常执行收尾）。
     */
    public QualityExecutionPlanDTO buildPlan(Long jobId) {
        QualityJob job = requireJob(jobId);
        List<QualityRule> rules = listEnabledRulesByJob(jobId);
        if (rules.isEmpty()) {
            logger.warn("质量检查任务无启用规则: jobId={}", jobId);
        }
        QualityExecutionPlanDTO plan = new QualityExecutionPlanDTO();
        plan.setJobId(job.getId());
        plan.setJobName(job.getName());
        plan.setAlertLevel(job.getAlertLevel());
        plan.setTimeoutMinutes(job.getTimeoutMinutes());
        plan.setRules(rules.stream().map(this::toPlanItem).toList());
        return plan;
    }

    /**
     * 按单规则装配执行计划（executeRule 路径）：规则所属任务可空，jobId 随之可空。
     */
    public QualityExecutionPlanDTO buildPlanByRule(Long ruleId) {
        QualityRule rule = requireRule(ruleId);
        QualityJob job = rule.getJobId() == null ? null : jobMapper.selectById(rule.getJobId());
        QualityExecutionPlanDTO plan = new QualityExecutionPlanDTO();
        plan.setJobId(job == null ? null : job.getId());
        plan.setJobName(job == null ? null : job.getName());
        plan.setAlertLevel(job == null ? null : job.getAlertLevel());
        plan.setTimeoutMinutes(job == null ? null : job.getTimeoutMinutes());
        plan.setRules(List.of(toPlanItem(rule)));
        return plan;
    }

    /**
     * 规则 → 执行计划项：读模板 + 元数据表，用 {@link RuleSqlGenerator} 展开占位符生成最终执行 SQL。
     * 阈值只透出，判定（determineLevel）留在 worker 侧。
     */
    private QualityExecutionPlanDTO.RulePlanItem toPlanItem(QualityRule rule) {
        QualityExecutionPlanDTO.RulePlanItem item = new QualityExecutionPlanDTO.RulePlanItem();
        item.setRuleId(rule.getId());
        item.setRuleName(rule.getName());
        item.setRuleType(rule.getType());
        item.setTableId(rule.getTableId());
        item.setWarningThreshold(rule.getWarningThreshold());
        item.setSevereThreshold(rule.getSevereThreshold());
        // 微服务化 4.2 契约补漏：结果值提取列名透出给 worker（原 worker 侧直接读规则实体）
        item.setResultMetric(rule.getResultMetric());
        // comparator：规则实体暂无该字段，预留透出（worker 侧按源 determineLevel 的 ≥ 语义判定）
        item.setComparator(null);
        MetadataTable table = rule.getTableId() == null ? null : tableMapper.selectById(rule.getTableId());
        if (table != null) {
            item.setTableName(table.getTableName());
            item.setDatasourceId(table.getDatasourceId());
        }
        // SQL 生成失败不阻断整个计划：executedSql 置空，由 worker 按源 executeSingleRule 的失败路径
        // 落 UNAVAILABLE 明细（对齐源 generateSqlSafe 兜底语义）
        item.setExecutedSql(generateSqlSafe(rule));
        return item;
    }

    /**
     * 生成规则最终校验 SQL（模板占位符展开）。对齐源 QualityCheckService.generateSql。
     */
    private String generateSql(QualityRule rule) {
        QualityRuleTemplate template = rule.getTemplateId() == null
                ? null : templateMapper.selectById(rule.getTemplateId());
        MetadataTable table = rule.getTableId() == null ? null : tableMapper.selectById(rule.getTableId());
        String sql = RuleSqlGenerator.generate(template, table, rule.getColumnName(),
                rule.getRangeMin(), rule.getRangeMax(), rule.getSqlExpression());
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_SQL_GENERATE_FAILED,
                    "规则校验 SQL 为空: " + rule.getName());
        }
        return sql;
    }

    /** 生成失败时兜底尝试（对齐源 generateSqlSafe：失败不抛，返回 null）。 */
    private String generateSqlSafe(QualityRule rule) {
        try {
            return generateSql(rule);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 批次 / 明细生命周期 ====================

    /**
     * 初始化 RUNNING 批次（对齐源 initBatch / executeRule 的批次初始化：单规则执行 jobId 为空）。
     *
     * @return 新建的批次 ID
     */
    @Transactional
    public Long createBatch(QualityBatchCreateRequest request) {
        QualityCheckBatch batch = new QualityCheckBatch();
        batch.setJobId(request.getJobId());
        batch.setJobName(request.getJobName());
        batch.setTriggerType(request.getTriggerType());
        batch.setStatus("RUNNING");
        batch.setStartedAt(LocalDateTime.now());
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        logger.info("质量检查批次初始化: batchId={}, jobId={}, triggerType={}",
                batch.getId(), request.getJobId(), request.getTriggerType());
        return batch.getId();
    }

    /**
     * 单条明细落库（对齐源 executeSingleRule 的落库字段：ruleType/resultMetric 按规则回填）。
     * 单规则批次（job_id 为空）时，对齐源 executeRule 路径：明细落库后把 batch.jobName
     * 更新为「规则名（表名）」，便于用户定位是哪个规则。
     *
     * @return 新建的明细 ID
     */
    @Transactional
    public Long saveDetail(Long batchId, QualityDetailCreateRequest request) {
        QualityCheckBatch batch = requireBatch(batchId);
        QualityRule rule = request.getRuleId() == null ? null : ruleMapper.selectById(request.getRuleId());

        QualityCheckDetail detail = new QualityCheckDetail();
        detail.setBatchId(batchId);
        detail.setRuleId(request.getRuleId());
        detail.setRuleName(request.getRuleName());
        detail.setTableId(request.getTableId());
        detail.setExecutedSql(request.getExecutedSql());
        detail.setResultValue(request.getResultValue());
        detail.setResultLevel(request.getResultLevel());
        detail.setSuccess(request.getSuccess());
        detail.setErrorMessage(request.getErrorMessage());
        detail.setCreatedAt(LocalDateTime.now());
        if (rule != null) {
            detail.setRuleType(rule.getType());
            detail.setResultMetric(rule.getResultMetric());
        }
        detailMapper.insert(detail);

        // 单规则定位：用规则名 + 目标表名更新批次任务名（对用户展示"规则名（表名）"）
        if (batch.getJobId() == null && request.getTableId() != null) {
            String tableName = request.getTableName();
            if (tableName == null || tableName.isBlank()) {
                MetadataTable table = tableMapper.selectById(request.getTableId());
                tableName = table == null ? null : table.getTableName();
            }
            String ruleName = request.getRuleName() != null ? request.getRuleName()
                    : (rule == null ? null : rule.getName());
            if (ruleName != null) {
                String displayName = (tableName == null || tableName.isBlank())
                        ? ruleName
                        : ruleName + "（" + tableName + "）";
                QualityCheckBatch update = new QualityCheckBatch();
                update.setId(batchId);
                update.setJobName(displayName);
                batchMapper.updateById(update);
            }
        }
        return detail.getId();
    }

    /**
     * 批次收尾串联（对齐源 executeJob 收尾顺序）：
     * 批次终态回写 → quality_job.last_trigger_at 更新 → 涉及表评分重算 → 合并告警。
     * 终态（SUCCESS/PARTIAL_FAILED/FAILED）由 worker 按成功/失败计数判定后透传
     * （对齐源 finishBatch：全成功=SUCCESS，部分失败=PARTIAL_FAILED，全失败=FAILED）。
     * 单规则批次（job_id 为空）不更新 last_trigger_at、不触发告警（对齐源 executeRule）。
     */
    public void finishBatch(Long batchId, QualityBatchFinishRequest request) {
        QualityCheckBatch batch = requireBatch(batchId);
        LocalDateTime endedAt = parseDateTimeOrNow(request.getEndedAt());
        batch.setEndedAt(endedAt);
        Long durationMs = request.getDurationMs();
        if (durationMs == null && batch.getStartedAt() != null) {
            // worker 未传耗时按源 finishBatch 语义兜底：startedAt → endedAt
            durationMs = Duration.between(batch.getStartedAt(), endedAt).toMillis();
        }
        batch.setDurationMs(durationMs);
        batch.setStatus(request.getStatus());
        batchMapper.updateById(batch);
        logger.info("质量检查批次收尾: batchId={}, jobId={}, status={}", batchId, batch.getJobId(), request.getStatus());

        // 涉及表集合：本批次明细去重 tableId，供评分跨任务聚合重算
        List<Long> tableIds = listDetailsByBatch(batchId).stream()
                .map(QualityCheckDetail::getTableId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (batch.getJobId() != null) {
            updateJobLastTriggerAt(batch.getJobId());
        }
        // 表级评分：批次收尾后按涉及表跨任务聚合重算，评分与告警基于同一批最新结果
        scoreCalculator.recalculateForTables(tableIds);
        if (batch.getJobId() != null) {
            fireBatchAlert(jobMapper.selectById(batch.getJobId()), batch);
        }
    }

    /**
     * 批次信息查询（worker 超时回调 checkAndFireTimeout 用：仍 RUNNING 才触发 TIMEOUT 告警）。
     */
    public QualityBatchInfoDTO batchInfo(Long batchId) {
        QualityCheckBatch batch = requireBatch(batchId);
        QualityBatchInfoDTO dto = new QualityBatchInfoDTO();
        dto.setId(batch.getId());
        dto.setStatus(batch.getStatus());
        dto.setAlertSent(batch.getAlertSent());
        return dto;
    }

    private void updateJobLastTriggerAt(Long jobId) {
        try {
            LocalDateTime now = LocalDateTime.now();
            jobMapper.update(null, new UpdateWrapper<QualityJob>()
                    .eq("id", jobId)
                    .set("last_trigger_at", now)
                    .set("updated_at", now));
        } catch (Exception e) {
            logger.warn("更新质量任务 last_trigger_at 失败: jobId={}", jobId, e);
        }
    }

    // ==================== 合并告警（自源 fireBatchAlert 搬运，告警走 AlertApi Feign） ====================

    /**
     * 批次收尾后触发分级合并告警（Sprint 6）。
     * <ul>
     *   <li>任务未配置 alert_level（SEVERE_ONLY / SEVERE_WARNING）→ 不触发</li>
     *   <li>批次已发送（alert_sent=1）→ 跳过（幂等）</li>
     *   <li>按任务触发等级过滤达到等级的明细：SEVERE 必触发；WARNING 仅在 SEVERE_WARNING 时触发</li>
     *   <li>达到等级的明细非空 → 发「失败」类告警（ALERT_FAILURE）</li>
     *   <li>无达到等级的明细且批次全部执行成功（SUCCESS）→ 发「成功」通知（ALERT_SUCCESS），
     *       表示质量检查全部通过；执行失败/部分失败（PARTIAL_FAILED/FAILED）不发成功通知</li>
     *   <li>合并为一条邮件（fireBatch），只写一条 alert_history（summary 聚合本次命中的多条规则）</li>
     * </ul>
     * 告警实际是否发出由 alert_rule（QUALITY 类型 + 接收用户 + 触发条件）决定；未配置规则则静默跳过。
     */
    private void fireBatchAlert(QualityJob job, QualityCheckBatch batch) {
        if (job == null || batch == null) {
            return;
        }
        if (job.getAlertLevel() == null || job.getAlertLevel().isBlank()) {
            return;
        }
        if (batch.getAlertSent() != null && batch.getAlertSent() == 1) {
            return;
        }
        List<QualityCheckDetail> details = listDetailsByBatch(batch.getId());
        if (details.isEmpty()) {
            return;
        }
        boolean severeOnly = "SEVERE_ONLY".equalsIgnoreCase(job.getAlertLevel());
        List<AlertItem> items = new ArrayList<>();
        for (QualityCheckDetail d : details) {
            if (!isAlertable(d.getResultLevel(), severeOnly)) {
                continue;
            }
            items.add(toAlertItem(d));
        }
        try {
            if (!items.isEmpty()) {
                // 失败类：存在达到任务告警等级的规则 → 发「失败」告警
                fireBatchAlert(AlertConstants.OBJECT_TYPE_QUALITY, job.getId(),
                        AlertConstants.ALERT_FAILURE, items, batch.getId());
            } else if ("SUCCESS".equalsIgnoreCase(batch.getStatus())) {
                // 成功类：批次全部执行成功且判定层无达到告警等级的规则 → 发「成功」通知
                // （执行失败/部分失败不发成功通知，避免误报）
                List<AlertItem> okItems = details.stream()
                        .map(this::toAlertItem)
                        .toList();
                fireBatchAlert(AlertConstants.OBJECT_TYPE_QUALITY, job.getId(),
                        AlertConstants.ALERT_SUCCESS, okItems, batch.getId());
            }
        } catch (Exception e) {
            logger.warn("质量分级告警触发失败: jobId={}, batchId={}", job.getId(), batch.getId(), e);
        } finally {
            // 无论是否命中规则，本批次都标记为已处理，避免重复尝试
            markAlertSent(batch.getId());
        }
    }

    /** 质量明细 → alert-api 批量告警条目 */
    private AlertItem toAlertItem(QualityCheckDetail d) {
        AlertItem item = new AlertItem();
        item.setLevel(d.getResultLevel());
        item.setRuleName(d.getRuleName());
        item.setDetail(buildDetailDesc(d));
        return item;
    }

    /**
     * 经 alert-service 远程批量触发告警（Feign，含质量批次 batchId）。
     * 失败经 RemoteCalls 降级（warn + 计数）按「未发送」处理，不影响主执行流程（最终一致）。
     *
     * @return 是否发送成功（Result 拆信封；异常按 false）
     */
    private boolean fireBatchAlert(String objectType, Long objectId, String alertType,
                                   List<AlertItem> items, Long batchId) {
        return RemoteCalls.execute("alert.fireBatch", () -> {
            AlertFireBatchRequest request = new AlertFireBatchRequest();
            request.setObjectType(objectType);
            request.setObjectId(objectId);
            request.setAlertType(alertType);
            request.setItems(items);
            request.setBatchId(batchId);
            var result = alertApi.fireBatch(request);
            return result != null && Boolean.TRUE.equals(result.data());
        }, false);
    }

    /**
     * 判断某分级是否达到当前任务的告警触发等级。
     * 严重必触发；警告仅在「严重+警告」模式下触发；通过/不可用不触发（R2：SQL 失败/UNAVAILABLE 不告警）。
     */
    private boolean isAlertable(String level, boolean severeOnly) {
        if (level == null) {
            return false;
        }
        if (AlertConstants.QUALITY_LEVEL_SEVERE.equals(level)) {
            return true;
        }
        if (AlertConstants.QUALITY_LEVEL_WARNING.equals(level)) {
            return !severeOnly;
        }
        return false;
    }

    /** 规则类型 → 中文展示（与前端 QUALITY_TYPE_LABEL 对齐），避免邮件/告警详情出现英文枚举 */
    private static final java.util.Map<String, String> RULE_TYPE_LABEL = java.util.Map.of(
            "COMPLETENESS", "完整性",
            "UNIQUENESS", "唯一性",
            "RANGE", "值域范围",
            "CUSTOM_SQL", "自定义 SQL"
    );

    /** 判定等级 → 中文展示（与前端 QUALITY_CHECK_LEVEL_LABEL 对齐） */
    private static final java.util.Map<String, String> LEVEL_LABEL = java.util.Map.of(
            AlertConstants.QUALITY_LEVEL_PASS, "通过",
            AlertConstants.QUALITY_LEVEL_WARNING, "警告",
            AlertConstants.QUALITY_LEVEL_SEVERE, "严重",
            AlertConstants.QUALITY_LEVEL_UNAVAILABLE, "不可用"
    );

    /**
     * 数字格式化：去尾零 + 最多 4 位小数（避免 0.166670 / 1.000000 这种长尾零看着乱）。
     * null 返回 null。
     */
    private static String formatNumber(BigDecimal v) {
        if (v == null) return null;
        BigDecimal stripped = v.stripTrailingZeros();
        String s;
        if (stripped.scale() <= 0) {
            // 整数（如 1.000000 → 1）
            s = stripped.toBigInteger().toString();
        } else if (stripped.scale() > 4) {
            s = stripped.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } else {
            s = stripped.toPlainString();
        }
        return s;
    }

    /**
     * 构建单条命中明细描述（用于邮件正文/告警详情 summary）。
     * <p>
     * 输出格式示例：{@code 类型:完整性 ｜ 结果值:0.1667 ｜ 阈值:警告≥0 · 严重≥0.2 → 警告}
     * <ul>
     *   <li>类型与等级用中文（避免英文枚举出现在用户界面/邮件）</li>
     *   <li>字段用全角竖线「｜」分隔，便于前端按段解析并结构化展示</li>
     *   <li>数字去尾零 + 最多 4 位小数，避免 0.166670 / 1.000000 这种长尾零看着乱</li>
     * </ul>
     */
    private String buildDetailDesc(QualityCheckDetail d) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (d.getRuleType() != null) {
            sb.append("类型:").append(RULE_TYPE_LABEL.getOrDefault(d.getRuleType(), d.getRuleType()));
            first = false;
        }
        String valueText = formatNumber(d.getResultValue());
        if (valueText != null) {
            if (!first) sb.append(" ｜ ");
            sb.append("结果值:").append(valueText);
            first = false;
        }
        // 判定依据：阈值区间 + 命中档位，让收件人无需进系统即可理解"为什么判严重"
        if (d.getRuleId() != null) {
            QualityRule rule = ruleMapper.selectById(d.getRuleId());
            if (rule != null && (rule.getWarningThreshold() != null || rule.getSevereThreshold() != null)) {
                if (!first) sb.append(" ｜ ");
                sb.append("阈值:");
                boolean firstTh = true;
                String warnText = formatNumber(rule.getWarningThreshold());
                if (warnText != null) {
                    sb.append("警告≥").append(warnText);
                    firstTh = false;
                }
                String sevText = formatNumber(rule.getSevereThreshold());
                if (sevText != null) {
                    if (!firstTh) sb.append(" · ");
                    sb.append("严重≥").append(sevText);
                }
                // 末尾追加命中档位（中文，与前端徽章对应；用户反馈"只是把英文换成中文，不要去掉内容"）
                if (d.getResultLevel() != null) {
                    sb.append(" → ").append(LEVEL_LABEL.getOrDefault(d.getResultLevel(), d.getResultLevel()));
                }
            }
        }
        if (d.getErrorMessage() != null && !d.getErrorMessage().isBlank()) {
            if (!first) sb.append(" ｜ ");
            sb.append("错误:").append(d.getErrorMessage());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void markAlertSent(Long batchId) {
        try {
            QualityCheckBatch update = new QualityCheckBatch();
            update.setId(batchId);
            update.setAlertSent(1);
            batchMapper.updateById(update);
        } catch (Exception e) {
            logger.warn("标记批次告警已发送失败: batchId={}", batchId, e);
        }
    }

    // ==================== private ====================

    private QualityJob requireJob(Long id) {
        QualityJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new BusinessException(ErrorCode.QUALITY_JOB_NOT_FOUND, "质量任务不存在: " + id);
        }
        return job;
    }

    private QualityRule requireRule(Long id) {
        QualityRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + id);
        }
        return rule;
    }

    private QualityCheckBatch requireBatch(Long id) {
        QualityCheckBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_BATCH_NOT_FOUND, "质量检查批次不存在: " + id);
        }
        return batch;
    }

    private List<QualityCheckDetail> listDetailsByBatch(Long batchId) {
        return detailMapper.selectList(new QueryWrapper<QualityCheckDetail>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    /**
     * 取任务引用且启用的规则（经 quality_job_rule 关联表）。
     */
    private List<QualityRule> listEnabledRulesByJob(Long jobId) {
        return ruleMapper.selectList(new QueryWrapper<QualityRule>()
                .inSql("id", "SELECT rule_id FROM quality_job_rule WHERE job_id = " + jobId)
                .eq("enabled", 1)
                .orderByAsc("id"));
    }

    /** ISO 时间解析，空/非法按当前时间兜底（批次收尾必须落 endedAt）。 */
    private LocalDateTime parseDateTimeOrNow(String text) {
        if (text == null || text.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            logger.warn("批次结束时间解析失败，按当前时间兜底: endedAt={}", text);
            return LocalDateTime.now();
        }
    }
}
