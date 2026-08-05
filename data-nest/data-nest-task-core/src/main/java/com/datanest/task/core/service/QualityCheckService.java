package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.PageResult;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.dto.QualityCheckBatchDTO;
import com.datanest.task.core.dto.QualityCheckDetailDTO;
import com.datanest.task.core.dto.QualityCheckQueryRequest;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.QualityCheckBatch;
import com.datanest.task.core.entity.QualityCheckDetail;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.entity.QualityRule;
import com.datanest.task.core.entity.QualityRuleTemplate;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.QualityCheckBatchMapper;
import com.datanest.task.core.mapper.QualityCheckDetailMapper;
import com.datanest.task.core.mapper.QualityJobMapper;
import com.datanest.task.core.mapper.QualityRuleMapper;
import com.datanest.task.core.mapper.QualityRuleTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 质量检查执行核心（Sprint 8 执行层）。
 * <p>
 * 在 app-worker 容器内运行（经 qualityCheckExecuteHandler 触发）。参照 {@link CollectExecutor} 渐进式落库：
 * batch/detail 逐条即时提交，不使用方法级事务——单条规则失败不阻塞其余规则，失败记录独立保留。
 * <p>
 * 结果值提取：RANGE 用 out_of_range/total 计算占比；其余按 result_metric 列名取，取不到降级首行首列。
 * <p>
 * 分级判定：按规则 warning_threshold / severe_threshold 计算 result_level（PASS/WARNING/SEVERE/UNAVAILABLE）落库。
 * 批次收尾后，若任务配置了告警（alert_level 非空）且存在 QUALITY 类型的 alert_rule，触发合并告警（fireBatch）。
 */
@Service
public class QualityCheckService {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckService.class);

    /** 内置 Doris 数据源 ID 标记（metadata_table.datasource_id = -1 时走 DorisSqlExecutor） */
    private static final long DORIS_DATASOURCE_ID = -1L;

    private final QualityCheckBatchMapper batchMapper;
    private final QualityCheckDetailMapper detailMapper;
    private final QualityJobMapper jobMapper;
    private final QualityRuleMapper ruleMapper;
    private final QualityRuleTemplateMapper templateMapper;
    private final MetadataTableMapper tableMapper;
    private final DataSourceConnectionMapper dataSourceMapper;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final GenericSqlExecutor genericSqlExecutor;
    private final AlertFiringService alertFiringService;

    public QualityCheckService(QualityCheckBatchMapper batchMapper,
                               QualityCheckDetailMapper detailMapper,
                               QualityJobMapper jobMapper,
                               QualityRuleMapper ruleMapper,
                               QualityRuleTemplateMapper templateMapper,
                               MetadataTableMapper tableMapper,
                               DataSourceConnectionMapper dataSourceMapper,
                               DorisSqlExecutor dorisSqlExecutor,
                               GenericSqlExecutor genericSqlExecutor,
                               AlertFiringService alertFiringService) {
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
        this.jobMapper = jobMapper;
        this.ruleMapper = ruleMapper;
        this.templateMapper = templateMapper;
        this.tableMapper = tableMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.genericSqlExecutor = genericSqlExecutor;
        this.alertFiringService = alertFiringService;
    }

    /**
     * 执行一个质量任务下引用且启用的全部规则。
     *
     * @return 新建的批次 ID
     */
    public Long executeJob(Long jobId, String triggerType) {
        QualityJob job = requireJob(jobId);
        QualityCheckBatch batch = initBatch(job, triggerType);
        logger.info("质量检查任务开始执行: batchId={}, jobId={}, triggerType={}", batch.getId(), jobId, triggerType);

        // 取任务引用且启用的规则（经 quality_job_rule 关联表）
        List<QualityRule> rules = listEnabledRulesByJob(jobId);
        if (rules.isEmpty()) {
            logger.warn("质量检查任务无启用规则: batchId={}, jobId={}", batch.getId(), jobId);
        }

        int success = 0;
        int failed = 0;
        for (QualityRule rule : rules) {
            boolean ok = executeSingleRule(rule, batch.getId());
            if (ok) {
                success++;
            } else {
                failed++;
            }
        }

        finishBatch(batch, success, failed, null);
        updateJobLastTriggerAt(jobId);
        fireBatchAlert(job, batch);
        logger.info("质量检查任务执行完成: batchId={}, jobId={}, success={}, failed={}", batch.getId(), jobId, success, failed);
        return batch.getId();
    }

    /**
     * 执行单条质量规则（独立批次，job_id 为空）。
     *
     * @return 新建的批次 ID
     */
    public Long executeRule(Long ruleId, String triggerType) {
        QualityRule rule = requireRule(ruleId);
        QualityJob job = rule.getJobId() == null ? null : jobMapper.selectById(rule.getJobId());
        QualityCheckBatch batch = new QualityCheckBatch();
        batch.setJobId(null);
        batch.setJobName(job == null ? "单规则执行" : job.getName());
        batch.setTriggerType(triggerType);
        batch.setStatus("RUNNING");
        batch.setStartedAt(LocalDateTime.now());
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        logger.info("单规则质量检查开始执行: batchId={}, ruleId={}", batch.getId(), ruleId);

        boolean ok = executeSingleRule(rule, batch.getId());
        finishBatch(batch, ok ? 1 : 0, ok ? 0 : 1, null);
        return batch.getId();
    }

    // ==================== 单规则执行 ====================

    /**
     * 执行一条规则并写明细。返回是否成功。
     */
    private boolean executeSingleRule(QualityRule rule, Long batchId) {
        QualityCheckDetail detail = new QualityCheckDetail();
        detail.setBatchId(batchId);
        detail.setRuleId(rule.getId());
        detail.setRuleName(rule.getName());
        detail.setRuleType(rule.getType());
        detail.setTableId(rule.getTableId());
        detail.setResultMetric(rule.getResultMetric());
        detail.setCreatedAt(LocalDateTime.now());

        try {
            String sql = generateSql(rule);
            detail.setExecutedSql(sql);
            BigDecimal value = executeAndExtract(rule, sql);
            detail.setResultValue(value);
            detail.setResultLevel(determineLevel(rule, value));
            detail.setSuccess(1);
            detailMapper.insert(detail);
            return true;
        } catch (Exception e) {
            logger.warn("质量规则执行失败: ruleId={}, ruleName={}, error={}", rule.getId(), rule.getName(), e.getMessage());
            detail.setSuccess(0);
            detail.setResultLevel(AlertConstants.QUALITY_LEVEL_UNAVAILABLE);
            detail.setErrorMessage(e.getMessage());
            if (detail.getExecutedSql() == null) {
                detail.setExecutedSql(generateSqlSafe(rule));
            }
            detailMapper.insert(detail);
            return false;
        }
    }

    /**
     * 按规则阈值计算分级：
     * <ul>
     *   <li>warning/severe 阈值都为空 → PASS（未配置分级，不告警）</li>
     *   <li>value &lt; warning → PASS</li>
     *   <li>warning ≤ value &lt; severe → WARNING</li>
     *   <li>value ≥ severe（或无 severe 阈值时 value ≥ warning）→ SEVERE</li>
     * </ul>
     */
    private String determineLevel(QualityRule rule, BigDecimal value) {
        BigDecimal warning = rule.getWarningThreshold();
        BigDecimal severe = rule.getSevereThreshold();
        if (warning == null && severe == null) {
            return AlertConstants.QUALITY_LEVEL_PASS;
        }
        if (value == null) {
            return AlertConstants.QUALITY_LEVEL_PASS;
        }
        if (severe != null && value.compareTo(severe) >= 0) {
            return AlertConstants.QUALITY_LEVEL_SEVERE;
        }
        if (warning != null && value.compareTo(warning) >= 0) {
            // 无 severe 阈值时，达到 warning 即视为严重（无严重上限）
            return severe == null ? AlertConstants.QUALITY_LEVEL_SEVERE : AlertConstants.QUALITY_LEVEL_WARNING;
        }
        return AlertConstants.QUALITY_LEVEL_PASS;
    }

    /**
     * 生成规则最终校验 SQL（模板占位符展开）。对齐 QualityRuleService.previewSql 逻辑。
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

    /** 生成失败时兜底尝试（用于明细记录实际 SQL，失败不抛）。 */
    private String generateSqlSafe(QualityRule rule) {
        try {
            return generateSql(rule);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在目标数据源执行校验 SQL 并提取结果值。
     */
    private BigDecimal executeAndExtract(QualityRule rule, String sql) {
        MetadataTable table = rule.getTableId() == null ? null : tableMapper.selectById(rule.getTableId());
        if (table == null) {
            throw new BusinessException(ErrorCode.QUALITY_TABLE_NOT_FOUND, "目标表不存在: " + rule.getTableId());
        }
        long datasourceId = table.getDatasourceId() == null ? DORIS_DATASOURCE_ID : table.getDatasourceId();

        if (datasourceId == DORIS_DATASOURCE_ID) {
            // 内置 Doris：返回 columns + rows(List<Map>)
            DorisSqlExecutor.QueryResult result = dorisSqlExecutor.query(sql);
            return extractFromDoris(result, rule);
        }
        // 其他注册数据源：经 GenericSqlExecutor（解密密码 + 建连接）
        DataSourceConnection ds = dataSourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED,
                    "数据源不存在: " + datasourceId);
        }
        GenericSqlExecutor.PreviewResult result = genericSqlExecutor.execute(ds, sql);
        if (!result.success) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED,
                    "校验 SQL 执行失败: " + result.error);
        }
        return extractFromGeneric(result, rule);
    }

    // ==================== 结果值提取 ====================

    /**
     * 从 Doris 查询结果提取结果值。rows 为 List&lt;Map&gt;，按列名 key 取。
     */
    private BigDecimal extractFromDoris(DorisSqlExecutor.QueryResult result, QualityRule rule) {
        List<Map<String, Object>> rows = result.rows();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED, "校验查询无返回行");
        }
        Map<String, Object> row = rows.get(0);
        if (isRange(rule)) {
            return computeRangeRatio(row);
        }
        String metric = rule.getResultMetric();
        Object metricVal = metric == null ? null : caseInsensitiveGet(row, metric);
        if (metricVal != null) {
            return toBigDecimal(metricVal);
        }
        // 兜底：首列
        String firstKey = row.keySet().isEmpty() ? null : row.keySet().iterator().next();
        if (firstKey == null) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED, "校验查询无列");
        }
        return toBigDecimal(row.get(firstKey));
    }

    /**
     * 从 GenericSqlExecutor 结果提取结果值。columns 为 List&lt;String&gt;，rows 为 List&lt;List&lt;Object&gt;&gt;。
     */
    private BigDecimal extractFromGeneric(GenericSqlExecutor.PreviewResult result, QualityRule rule) {
        List<String> columns = result.columns;
        List<List<Object>> rows = result.rows;
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED, "校验查询无返回结果");
        }
        List<Object> firstRow = rows.get(0);
        if (isRange(rule)) {
            int totalIdx = indexOfIgnoreCase(columns, "total");
            int outIdx = indexOfIgnoreCase(columns, "out_of_range");
            if (totalIdx >= 0 && outIdx >= 0) {
                BigDecimal total = toBigDecimal(valueAt(firstRow, totalIdx));
                BigDecimal out = toBigDecimal(valueAt(firstRow, outIdx));
                return ratio(out, total);
            }
        }
        String metric = rule.getResultMetric();
        if (metric != null) {
            int idx = indexOfIgnoreCase(columns, metric);
            if (idx >= 0) {
                return toBigDecimal(valueAt(firstRow, idx));
            }
        }
        // 兜底：首行首列
        return toBigDecimal(firstRow.isEmpty() ? null : firstRow.get(0));
    }

    /**
     * RANGE 规则占比 = out_of_range / total（total=0 时返回 0）。
     * 列名大小写不敏感匹配（Doris/MySQL JDBC 可能返回大写列名）。
     * out_of_range 可能是 NULL（空表时 SUM 返回 NULL），按 0 处理。
     */
    private BigDecimal computeRangeRatio(Map<String, Object> row) {
        if (!containsKeyIgnoreCase(row, "total") || !containsKeyIgnoreCase(row, "out_of_range")) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED, "值域规则查询缺少 total/out_of_range 列");
        }
        BigDecimal total = toBigDecimal(caseInsensitiveGet(row, "total"));
        BigDecimal out = toBigDecimal(caseInsensitiveGet(row, "out_of_range"));
        return ratio(out, total);
    }

    /**
     * 大小写不敏感从 Map 取值（key 不存在返回 null，与「值为 null」无法区分，需配合 containsKeyIgnoreCase）。
     */
    private Object caseInsensitiveGet(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 大小写不敏感判断 Map 是否包含指定 key。
     */
    private boolean containsKeyIgnoreCase(Map<String, Object> map, String key) {
        if (map == null) {
            return false;
        }
        if (map.containsKey(key)) {
            return true;
        }
        for (String k : map.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRange(QualityRule rule) {
        return "RANGE".equalsIgnoreCase(rule.getType());
    }

    private BigDecimal ratio(BigDecimal out, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return out.divide(total, 6, RoundingMode.HALF_UP);
    }

    private Object valueAt(List<Object> row, int idx) {
        return idx >= 0 && idx < row.size() ? row.get(idx) : null;
    }

    private int indexOfIgnoreCase(List<String> columns, String target) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i) != null && columns.get(i).equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            // 非数值结果（如自定义 SQL 返回字符串）按 0 处理，仅记录
            return BigDecimal.ZERO;
        }
    }

    // ==================== batch / detail 生命周期 ====================

    private QualityCheckBatch initBatch(QualityJob job, String triggerType) {
        QualityCheckBatch batch = new QualityCheckBatch();
        batch.setJobId(job.getId());
        batch.setJobName(job.getName());
        batch.setTriggerType(triggerType);
        batch.setStatus("RUNNING");
        batch.setStartedAt(LocalDateTime.now());
        batch.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        return batch;
    }

    /**
     * 收尾：更新批次终态。全成功=SUCCESS，部分失败=PARTIAL_FAILED，全失败=FAILED。
     */
    private void finishBatch(QualityCheckBatch batch, int success, int failed, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        batch.setEndedAt(now);
        batch.setDurationMs(Duration.between(batch.getStartedAt(), now).toMillis());
        if (failed == 0) {
            // 全成功（含无规则：无检查项视为执行成功）
            batch.setStatus("SUCCESS");
        } else if (success == 0) {
            // 全失败
            batch.setStatus("FAILED");
            batch.setErrorMessage(errorMessage);
        } else {
            // 部分失败
            batch.setStatus("PARTIAL_FAILED");
        }
        batchMapper.updateById(batch);
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

    /**
     * 批次收尾后触发分级合并告警（Sprint 6）。
     * <ul>
     *   <li>任务未配置 alert_level（SEVERE_ONLY / SEVERE_WARNING）→ 不触发</li>
     *   <li>批次已发送（alert_sent=1）→ 跳过（幂等）</li>
     *   <li>按任务触发等级过滤达到等级的明细：SEVERE/UNAVAILABLE 必触发；WARNING 仅在 SEVERE_WARNING 时触发</li>
     *   <li>合并为一条邮件（fireBatch），写 alert_history</li>
     * </ul>
     * 告警实际是否发出由 alert_rule（QUALITY 类型 + 接收用户）决定；未配置规则则静默跳过。
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
        List<AlertFiringService.AlertItem> items = new java.util.ArrayList<>();
        for (QualityCheckDetail d : details) {
            if (!isAlertable(d.getResultLevel(), severeOnly)) {
                continue;
            }
            items.add(new AlertFiringService.AlertItem(d.getResultLevel(), d.getRuleName(), buildDetailDesc(d)));
        }
        if (items.isEmpty()) {
            return;
        }
        try {
            alertFiringService.fireBatch(AlertConstants.OBJECT_TYPE_QUALITY, job.getId(),
                    AlertConstants.ALERT_FAILURE, items);
        } catch (Exception e) {
            logger.warn("质量分级告警触发失败: jobId={}, batchId={}", job.getId(), batch.getId(), e);
        } finally {
            // 无论是否命中规则，本批次都标记为已处理，避免重复尝试
            markAlertSent(batch.getId());
        }
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

    /** 构建单条异常明细描述（用于邮件正文/告警详情）。 */
    private String buildDetailDesc(QualityCheckDetail d) {
        StringBuilder sb = new StringBuilder();
        if (d.getRuleType() != null) {
            sb.append("类型:").append(d.getRuleType());
        }
        if (d.getResultValue() != null) {
            sb.append(", 结果值:").append(d.getResultValue());
        }
        if (d.getErrorMessage() != null && !d.getErrorMessage().isBlank()) {
            sb.append(", 错误:").append(d.getErrorMessage());
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

    // ==================== 查询（供 QualityCheckController 使用） ====================

    public QualityCheckBatch requireBatch(Long id) {
        QualityCheckBatch batch = batchMapper.selectById(id);
        if (batch == null) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_BATCH_NOT_FOUND, "质量检查批次不存在: " + id);
        }
        return batch;
    }

    public List<QualityCheckDetail> listDetailsByBatch(Long batchId) {
        return detailMapper.selectList(new QueryWrapper<QualityCheckDetail>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    /**
     * 分页查询批次列表（按 job / trigger_type / status 过滤）。
     */
    public PageResult<QualityCheckBatchDTO> listBatches(QualityCheckQueryRequest request) {
        QueryWrapper<QualityCheckBatch> wrapper = new QueryWrapper<>();
        if (request.getJobId() != null) {
            wrapper.eq("job_id", request.getJobId());
        }
        if (request.getTriggerType() != null && !request.getTriggerType().isBlank()) {
            wrapper.eq("trigger_type", request.getTriggerType());
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            wrapper.eq("status", request.getStatus());
        }
        wrapper.orderByDesc("id");

        IPage<QualityCheckBatch> page = batchMapper.selectPage(
                new Page<>(request.getPage(), request.getPageSize()), wrapper);
        List<QualityCheckBatchDTO> records = page.getRecords().stream()
                .map(this::toBatchDTO)
                .toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 批次详情（含明细）。
     */
    public QualityCheckBatchDTO getBatchDetail(Long batchId) {
        QualityCheckBatch batch = requireBatch(batchId);
        QualityCheckBatchDTO dto = toBatchDTO(batch);
        List<QualityCheckDetailDTO> details = listDetailsByBatch(batchId).stream()
                .map(this::toDetailDTO)
                .toList();
        dto.setDetails(details);
        return dto;
    }

    private QualityCheckBatchDTO toBatchDTO(QualityCheckBatch batch) {
        QualityCheckBatchDTO dto = new QualityCheckBatchDTO();
        dto.setId(batch.getId());
        dto.setJobId(batch.getJobId());
        dto.setJobName(batch.getJobName());
        dto.setTriggerType(batch.getTriggerType());
        dto.setStatus(batch.getStatus());
        dto.setStartedAt(batch.getStartedAt());
        dto.setEndedAt(batch.getEndedAt());
        dto.setDurationMs(batch.getDurationMs());
        dto.setErrorMessage(batch.getErrorMessage());
        dto.setCreatedAt(batch.getCreatedAt());
        // 汇总规则数/成功/失败
        List<QualityCheckDetail> details = listDetailsByBatch(batch.getId());
        dto.setRuleCount(details.size());
        dto.setSuccessCount((int) details.stream().filter(d -> d.getSuccess() != null && d.getSuccess() == 1).count());
        dto.setFailedCount(details.size() - (dto.getSuccessCount() == null ? 0 : dto.getSuccessCount()));
        return dto;
    }

    private QualityCheckDetailDTO toDetailDTO(QualityCheckDetail detail) {
        QualityCheckDetailDTO dto = new QualityCheckDetailDTO();
        dto.setId(detail.getId());
        dto.setBatchId(detail.getBatchId());
        dto.setRuleId(detail.getRuleId());
        dto.setRuleName(detail.getRuleName());
        dto.setRuleType(detail.getRuleType());
        dto.setTableId(detail.getTableId());
        dto.setResultMetric(detail.getResultMetric());
        dto.setResultValue(detail.getResultValue());
        dto.setResultLevel(detail.getResultLevel());
        dto.setSuccess(detail.getSuccess());
        dto.setErrorMessage(detail.getErrorMessage());
        dto.setExecutedSql(detail.getExecutedSql());
        dto.setCreatedAt(detail.getCreatedAt());
        if (detail.getTableId() != null) {
            MetadataTable table = tableMapper.selectById(detail.getTableId());
            if (table != null) {
                dto.setTableName(table.getTableName());
            }
        }
        return dto;
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

    /**
     * 取任务引用且启用的规则（经 quality_job_rule 关联表）。
     */
    private List<QualityRule> listEnabledRulesByJob(Long jobId) {
        return ruleMapper.selectList(new QueryWrapper<QualityRule>()
                .inSql("id", "SELECT rule_id FROM quality_job_rule WHERE job_id = " + jobId)
                .eq("enabled", 1)
                .orderByAsc("id"));
    }
}
