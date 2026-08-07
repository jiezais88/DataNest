package com.datanest.task.core.service;

import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.governance.api.QualityExecutionApi;
import com.datanest.governance.api.dto.QualityBatchCreateRequest;
import com.datanest.governance.api.dto.QualityBatchFinishRequest;
import com.datanest.governance.api.dto.QualityBatchInfoDTO;
import com.datanest.governance.api.dto.QualityDetailCreateRequest;
import com.datanest.governance.api.dto.QualityExecutionPlanDTO;
import com.datanest.governance.api.dto.QualityExecutionPlanRequest;
import com.datanest.governance.api.dto.QualityRulePlanRequest;
import com.datanest.task.core.constant.AlertConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 质量检查执行核心（Sprint 8 执行层，worker 侧）。
 * <p>
 * 在 app-worker 容器内运行（经 qualityCheckExecuteHandler 触发）。
 * 微服务化 4.2：治理表读写全部改为经 {@link QualityExecutionApi} Feign 调 app-governance——
 * 执行计划装配（规则/模板/元数据表/执行 SQL）、批次与明细落库、批次收尾串联
 * （终态回写 + last_trigger_at + 评分重算 + 合并告警）均在服务端完成；
 * 本类只负责执行 SQL（本地 Doris/Generic 执行器）与阈值判定（determineLevel）。
 * <p>
 * 容错红线：执行开始处（plan / plan-by-rule / 批次 init）远程失败 fail-fast；
 * 执行中明细落库与批次收尾 RemoteCalls 降级记 error（渐进落库 + 对账兜底语义不变）。
 * 查询路径（批次列表/详情）已随 4.2 移入 governance 本地 QualityCheckQueryService。
 */
@Service
public class QualityCheckService {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckService.class);

    /** 内置 Doris 数据源 ID 标记（metadata_table.datasource_id = -1 时走 DorisSqlExecutor） */
    private static final long DORIS_DATASOURCE_ID = -1L;

    /** 批次执行超时检测调度器（守护线程，不阻止 JVM 退出） */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newScheduledThreadPool(2, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "quality-timeout-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 批次 ID → 等待中的超时检测任务；批次正常结束/异常时移除并取消，避免误判为超时 */
    private static final ConcurrentHashMap<Long, ScheduledFuture<?>> BATCH_TIMEOUTS = new ConcurrentHashMap<>();

    private final QualityExecutionApi qualityExecutionApi;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final GenericSqlExecutor genericSqlExecutor;
    private final AlertApi alertApi;

    public QualityCheckService(QualityExecutionApi qualityExecutionApi,
                               DorisSqlExecutor dorisSqlExecutor,
                               GenericSqlExecutor genericSqlExecutor,
                               AlertApi alertApi) {
        this.qualityExecutionApi = qualityExecutionApi;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.genericSqlExecutor = genericSqlExecutor;
        this.alertApi = alertApi;
    }

    /**
     * 执行一个质量任务下引用且启用的全部规则。
     *
     * @return 新建的批次 ID
     */
    public Long executeJob(Long jobId, String triggerType) {
        // 执行开始 fail-fast：计划装配/批次 init 读不到不启动执行
        QualityExecutionPlanDTO plan = planOrThrow(jobId);
        Long batchId = createBatchOrThrow(plan.getJobId(), plan.getJobName(), triggerType);
        logger.info("质量检查任务开始执行: batchId={}, jobId={}, triggerType={}", batchId, jobId, triggerType);

        // 执行超时检测：任务配置了 timeoutMinutes 时，到点后若批次仍 RUNNING 则触发 TIMEOUT 告警。
        // 注意：不取消正在执行的规则（避免 SQL 中断风险），让后台自然跑完；超时只触发告警，不改批次终态。
        scheduleTimeoutWatch(plan, batchId);

        try {
            // 规则空列表合法：无检查项视为执行成功（init + finish SUCCESS 批次）
            List<QualityExecutionPlanDTO.RulePlanItem> rules = plan.getRules() == null ? List.of() : plan.getRules();
            if (rules.isEmpty()) {
                logger.warn("质量检查任务无启用规则: batchId={}, jobId={}", batchId, jobId);
            }

            int success = 0;
            int failed = 0;
            for (QualityExecutionPlanDTO.RulePlanItem rule : rules) {
                boolean ok = executeSingleRule(rule, batchId);
                if (ok) {
                    success++;
                } else {
                    failed++;
                }
            }

            finishBatchDegraded(batchId, batchStatus(success, failed));
            logger.info("质量检查任务执行完成: batchId={}, jobId={}, success={}, failed={}", batchId, jobId, success, failed);
            return batchId;
        } finally {
            // 无论正常完成或异常，都取消超时检测，避免超时回调误判
            cancelTimeoutWatch(batchId);
        }
    }

    /**
     * 执行单条质量规则（独立批次，job_id 为空）。
     *
     * @return 新建的批次 ID
     */
    public Long executeRule(Long ruleId, String triggerType) {
        // 执行开始 fail-fast：计划装配/批次 init 读不到不启动执行
        QualityExecutionPlanDTO plan = planByRuleOrThrow(ruleId);
        List<QualityExecutionPlanDTO.RulePlanItem> rules = plan.getRules() == null ? List.of() : plan.getRules();
        if (rules.isEmpty()) {
            throw new BusinessException(ErrorCode.QUALITY_RULE_NOT_FOUND, "质量规则不存在: " + ruleId);
        }
        // 单规则执行：先落临时 jobName，明细落库后由服务端按规则名+表名更新，便于用户定位是哪个规则
        String jobName = plan.getJobName() == null ? "单规则执行" : plan.getJobName();
        Long batchId = createBatchOrThrow(null, jobName, triggerType);
        logger.info("单规则质量检查开始执行: batchId={}, ruleId={}", batchId, ruleId);

        boolean ok = executeSingleRule(rules.get(0), batchId);
        finishBatchDegraded(batchId, batchStatus(ok ? 1 : 0, ok ? 0 : 1));
        return batchId;
    }

    /**
     * 批次终态：全成功=SUCCESS（含无规则），部分失败=PARTIAL_FAILED，全失败=FAILED。
     */
    private String batchStatus(int success, int failed) {
        if (failed == 0) {
            return "SUCCESS";
        }
        return success == 0 ? "FAILED" : "PARTIAL_FAILED";
    }

    /**
     * 注册批次超时检测：到点后检查批次是否仍 RUNNING，若是则触发 TIMEOUT 告警。
     */
    private void scheduleTimeoutWatch(QualityExecutionPlanDTO plan, Long batchId) {
        Integer minutes = plan.getTimeoutMinutes();
        if (minutes == null || minutes <= 0 || plan.getJobId() == null) {
            return;
        }
        ScheduledFuture<?> sf = TIMEOUT_SCHEDULER.schedule(() -> checkAndFireTimeout(plan.getJobId(), batchId, minutes),
                minutes, TimeUnit.MINUTES);
        BATCH_TIMEOUTS.put(batchId, sf);
    }

    private void cancelTimeoutWatch(Long batchId) {
        ScheduledFuture<?> sf = BATCH_TIMEOUTS.remove(batchId);
        if (sf != null) {
            sf.cancel(false);
        }
    }

    /**
     * 超时检测回调：批次仍 RUNNING → 触发 TIMEOUT 告警（用 fire 单条，无明细聚合）。
     * 幂等依赖 alert-service 的 countRecent 60s 防重。
     * 批次状态经 governance 远程读取（失败按已结束处理，不误报超时）。
     */
    private void checkAndFireTimeout(Long jobId, Long batchId, Integer minutes) {
        try {
            QualityBatchInfoDTO batch = RemoteCalls.execute("governance.quality.batch-info", () -> {
                Result<QualityBatchInfoDTO> result = qualityExecutionApi.batchInfo(batchId);
                return result == null ? null : result.data();
            }, null);
            if (batch == null) {
                return;
            }
            // 已结束的批次不算超时（执行可能在超时点前完成）
            if (!"RUNNING".equalsIgnoreCase(batch.getStatus())) {
                return;
            }
            logger.warn("质量任务执行超时: jobId={}, batchId={}, timeoutMinutes={}", jobId, batchId, minutes);
            fireAlert(AlertConstants.OBJECT_TYPE_QUALITY, jobId,
                    AlertConstants.ALERT_TIMEOUT,
                    "执行超时：超过 " + minutes + " 分钟仍未完成");
        } catch (Exception e) {
            logger.error("质量任务超时检测失败: jobId={}, batchId={}", jobId, batchId, e);
        }
    }

    // ==================== 单规则执行 ====================

    /**
     * 执行一条规则并经 Feign 落明细。返回是否执行成功（明细落库降级不影响该判定）。
     */
    private boolean executeSingleRule(QualityExecutionPlanDTO.RulePlanItem rule, Long batchId) {
        QualityDetailCreateRequest detail = new QualityDetailCreateRequest();
        detail.setRuleId(rule.getRuleId());
        detail.setRuleName(rule.getRuleName());
        detail.setTableId(rule.getTableId());
        detail.setTableName(rule.getTableName());

        String sql = rule.getExecutedSql();
        detail.setExecutedSql(sql);
        try {
            // 服务端生成 SQL 失败时 executedSql 为 null：对齐原 generateSql 抛错路径，直接落 UNAVAILABLE 明细不执行
            if (sql == null || sql.isBlank()) {
                throw new BusinessException(ErrorCode.QUALITY_CHECK_SQL_GENERATE_FAILED,
                        "规则校验 SQL 为空: " + rule.getRuleName());
            }
            // 模板类规则若残留占位符（如整表完整性未指定检查字段，{column} 未替换）则跳过，避免执行非法 SQL
            assertNoUnresolvedPlaceholder(sql, rule.getRuleName());
            BigDecimal value = executeAndExtract(rule, sql);
            detail.setResultValue(value);
            detail.setResultLevel(determineLevel(rule, value));
            detail.setSuccess(1);
            saveDetailDegraded(batchId, detail);
            return true;
        } catch (Exception e) {
            logger.warn("质量规则执行失败: ruleId={}, ruleName={}, error={}", rule.getRuleId(), rule.getRuleName(), e.getMessage());
            detail.setSuccess(0);
            detail.setResultLevel(AlertConstants.QUALITY_LEVEL_UNAVAILABLE);
            detail.setErrorMessage(e.getMessage());
            saveDetailDegraded(batchId, detail);
            return false;
        }
    }

    /**
     * 按规则阈值计算分级（QualityRule 无 comparator 字段，恒 ≥ 语义）：
     * <ul>
     *   <li>warning/severe 阈值都为空 → PASS（未配置分级，不告警）</li>
     *   <li>value &lt; warning → PASS</li>
     *   <li>warning ≤ value &lt; severe → WARNING</li>
     *   <li>value ≥ severe（或无 severe 阈值时 value ≥ warning）→ SEVERE</li>
     * </ul>
     */
    private String determineLevel(QualityExecutionPlanDTO.RulePlanItem rule, BigDecimal value) {
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
     * 校验生成的 SQL 无残留占位符（{column}/{min}/{max} 等）。
     * 模板类规则未指定检查字段时（如整表完整性检查），{column} 不会被替换，
     * 若直接执行会生成 COUNT() 等非法 SQL，故在此拦截并标记规则为不可用。
     */
    private void assertNoUnresolvedPlaceholder(String sql, String ruleName) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        int braceStart = sql.indexOf('{');
        if (braceStart >= 0 && sql.indexOf('}', braceStart) > braceStart) {
            throw new BusinessException(ErrorCode.QUALITY_CHECK_EXECUTE_FAILED,
                    "规则「" + ruleName + "」未指定检查字段，无法生成有效校验 SQL，请编辑规则补充检查字段");
        }
    }

    /**
     * 在目标数据源执行校验 SQL 并提取结果值。
     */
    private BigDecimal executeAndExtract(QualityExecutionPlanDTO.RulePlanItem rule, String sql) {
        if (rule.getTableName() == null) {
            throw new BusinessException(ErrorCode.QUALITY_TABLE_NOT_FOUND, "目标表不存在: " + rule.getTableId());
        }
        long datasourceId = rule.getDatasourceId() == null ? DORIS_DATASOURCE_ID : rule.getDatasourceId();

        if (datasourceId == DORIS_DATASOURCE_ID) {
            // 内置 Doris：返回 columns + rows(List<Map>)
            DorisSqlExecutor.QueryResult result = dorisSqlExecutor.query(sql);
            return extractFromDoris(result, rule);
        }
        // 其他注册数据源：经 GenericSqlExecutor（Feign 读连接，fail-fast + 解密密码 + 建连接）
        DataSourceInfo ds;
        try {
            ds = genericSqlExecutor.getDatasource(datasourceId);
        } catch (BusinessException e) {
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
    private BigDecimal extractFromDoris(DorisSqlExecutor.QueryResult result, QualityExecutionPlanDTO.RulePlanItem rule) {
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
    private BigDecimal extractFromGeneric(GenericSqlExecutor.PreviewResult result, QualityExecutionPlanDTO.RulePlanItem rule) {
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

    private boolean isRange(QualityExecutionPlanDTO.RulePlanItem rule) {
        return "RANGE".equalsIgnoreCase(rule.getRuleType());
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

    // ==================== 远程调用（governance 质量执行域） ====================

    /** 执行计划装配（按任务），fail-fast：读不到计划不启动执行。 */
    private QualityExecutionPlanDTO planOrThrow(Long jobId) {
        QualityExecutionPlanRequest request = new QualityExecutionPlanRequest();
        request.setJobId(jobId);
        Result<QualityExecutionPlanDTO> result = qualityExecutionApi.plan(request);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "质量执行计划装配失败（governance 不可达或任务不存在）: jobId=" + jobId);
        }
        return result.data();
    }

    /** 执行计划装配（按单规则），fail-fast：读不到计划不启动执行。 */
    private QualityExecutionPlanDTO planByRuleOrThrow(Long ruleId) {
        QualityRulePlanRequest request = new QualityRulePlanRequest();
        request.setRuleId(ruleId);
        Result<QualityExecutionPlanDTO> result = qualityExecutionApi.planByRule(request);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "质量执行计划装配失败（governance 不可达或规则不存在）: ruleId=" + ruleId);
        }
        return result.data();
    }

    /** 初始化 RUNNING 批次，fail-fast：批次建不出来不跑"无登记执行"。 */
    private Long createBatchOrThrow(Long jobId, String jobName, String triggerType) {
        QualityBatchCreateRequest request = new QualityBatchCreateRequest();
        request.setJobId(jobId);
        request.setJobName(jobName);
        request.setTriggerType(triggerType);
        Result<Long> result = qualityExecutionApi.createBatch(request);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "质量检查批次初始化失败（governance 不可达）: jobId=" + jobId);
        }
        return result.data();
    }

    /**
     * 单条明细落库（执行中写失败 RemoteCalls 降级记 error：渐进落库语义，单条丢失不阻塞其余规则）。
     * 服务端顺带按规则回填 ruleType/resultMetric，单规则批次时更新 batch.jobName 为「规则名（表名）」。
     */
    private void saveDetailDegraded(Long batchId, QualityDetailCreateRequest detail) {
        try {
            qualityExecutionApi.saveDetail(batchId, detail);
        } catch (Exception e) {
            logger.error("质量明细落库失败（降级，不阻塞执行）: batchId={}, ruleId={}", batchId, detail.getRuleId(), e);
        }
    }

    /**
     * 批次收尾（降级记 error）：终态回写 + last_trigger_at + 评分重算 + 合并告警全在服务端串联，
     * 失败靠对账语义兜底。
     */
    private void finishBatchDegraded(Long batchId, String status) {
        try {
            QualityBatchFinishRequest request = new QualityBatchFinishRequest();
            request.setStatus(status);
            // endedAt/durationMs 留空由服务端兜底（now / startedAt→endedAt）
            qualityExecutionApi.finishBatch(batchId, request);
        } catch (Exception e) {
            logger.error("质量批次收尾失败（降级，靠对账兜底）: batchId={}, status={}", batchId, status, e);
        }
    }

    /**
     * 经 alert-service 远程触发单条告警（Feign，超时告警用）。
     * 失败经 RemoteCalls 降级（warn + 计数）按「未发送」处理，不影响主执行流程（最终一致）。
     *
     * @return 是否发送成功（Result 拆信封；异常按 false）
     */
    private boolean fireAlert(String objectType, Long objectId, String alertType, String detail) {
        return RemoteCalls.execute("alert.fire", () -> {
            AlertFireRequest request = new AlertFireRequest();
            request.setObjectType(objectType);
            request.setObjectId(objectId);
            request.setAlertType(alertType);
            request.setDetail(detail);
            var result = alertApi.fire(request);
            return result != null && Boolean.TRUE.equals(result.data());
        }, false);
    }
}
