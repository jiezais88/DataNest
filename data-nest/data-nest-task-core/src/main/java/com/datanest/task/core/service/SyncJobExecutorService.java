package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.entity.SyncJobLog;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.mapper.SyncJobLogMapper;
import com.datanest.task.core.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量同步任务执行核心，供 data-nest-worker 与 engineering 同进程调用。
 * 不处理 XXL-JOB 调度注册与重试触发，仅负责任务的单次执行；
 * 失败时仅做重试的持久化登记（next_retry_at），到期扫描与触发由 job 模块负责。
 */
@Service
public class SyncJobExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobExecutorService.class);
    /** 日志批量写入批次大小 */
    private static final int LOG_BATCH_SIZE = 500;

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;
    private final AddaxJobService addaxJobService;
    private final MetadataRegistrationService metadataRegistrationService;
    private final SyncJobRetryService syncJobRetryService;
    private final AlertApi alertApi;
    private final QualityAutoTriggerService qualityAutoTriggerService;

    public SyncJobExecutorService(SyncJobMapper syncJobMapper,
                                  SyncJobHistoryMapper syncJobHistoryMapper,
                                  SyncJobLogMapper syncJobLogMapper,
                                  AddaxJobService addaxJobService,
                                  MetadataRegistrationService metadataRegistrationService,
                                  SyncJobRetryService syncJobRetryService,
                                  AlertApi alertApi,
                                  QualityAutoTriggerService qualityAutoTriggerService) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
        this.addaxJobService = addaxJobService;
        this.metadataRegistrationService = metadataRegistrationService;
        this.syncJobRetryService = syncJobRetryService;
        this.alertApi = alertApi;
        this.qualityAutoTriggerService = qualityAutoTriggerService;
    }

    /**
     * 不使用方法级事务：Addax 外部进程执行耗时分钟~小时级，长事务会长时间占连接；
     * 且 Addax 数据已写入 Doris 后事务回滚无法撤销，反而丢失 DB 侧历史/状态。
     * 状态翻转与日志写入逐条即时提交（MyBatis-Plus 单条 insert/update 自身 autocommit）。
     */
    public void runSyncJob(Long syncJobId, String triggerType, Long historyId) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        SyncJobHistory history = syncJobHistoryMapper.selectById(historyId);
        if (history == null) {
            history = initHistory(syncJobId, triggerType);
            historyId = history.getId();
        }

        // 日志行号：取一次起始行号后内存自增，避免每条日志一次 COUNT
        LogLineCounter lineCounter = new LogLineCounter(nextLineNum(history.getId()));

        updateExecutionStatus(job, ExecutionStatus.RUNNING.getCode());
        logInfo(history, lineCounter, "开始 Addax 同步执行, syncJobId=" + syncJobId + ", triggerType=" + triggerType);

        AddaxJobService.AddaxExecutionResult result = addaxJobService.execute(syncJobId, historyId);
        writeTableLogs(history, lineCounter, result);

        if (result.success()) {
            finishHistory(history, result, ExecutionStatus.SUCCESS.getCode());
            updateExecutionStatus(job, ExecutionStatus.SUCCESS.getCode());
            updateJobLastExecute(job, history.getId());
            try {
                metadataRegistrationService.register(syncJobId);
                logInfo(history, lineCounter, "同步成功，已注册 Doris 元数据");
            } catch (Exception e) {
                // 同步数据已成功落 Doris，元数据注册失败不应把整个任务标 FAILED，仅记录错误
                logger.error("Doris 元数据注册失败（不影响本次同步结果）: syncJobId={}", syncJobId, e);
                logError(history, lineCounter, "同步成功，但 Doris 元数据注册失败: " + e.getMessage());
            }
            // Sprint 5：同步任务成功告警（按 alert_rule 配置，经 alert-service 远程触发）
            fireAlert("SYNC_JOB", syncJobId, "SUCCESS", "同步任务执行成功，写入 "
                    + result.writeRows() + " 行");
            // Sprint 8：同步任务成功后触发绑定的质量任务自动检查
            triggerQualityOnSuccess(QualityAutoTriggerService.OBJECT_TYPE_SYNC_JOB, syncJobId);
            return;
        }

        // 手动停止防覆盖：watcher 强杀子进程后 Addax 以失败收尾，
        // 此处重读 history，若已被置为 TERMINATED 则不再覆盖为 FAILED，也不登记重试
        SyncJobHistory fresh = syncJobHistoryMapper.selectById(history.getId());
        if (fresh != null && !ExecutionStatus.RUNNING.getCode().equalsIgnoreCase(fresh.getStatus())) {
            logger.info("同步任务已被手动停止，跳过失败覆盖与重试登记: syncJobId={}, historyId={}, status={}",
                    syncJobId, history.getId(), fresh.getStatus());
            return;
        }

        logError(history, lineCounter, "Addax 执行失败: " + result.errorMessage());
        finishHistory(history, result, ExecutionStatus.FAILED.getCode());
        updateExecutionStatus(job, ExecutionStatus.FAILED.getCode());
        updateJobLastExecute(job, history.getId());
        logError(history, lineCounter, "同步任务最终失败");
        // Sprint 5：同步任务失败告警（按 alert_rule 配置，经 alert-service 远程触发）
        fireAlert("SYNC_JOB", syncJobId, "FAILURE", result.errorMessage());
        // 失败收尾：剩余重试次数 > 0 时在历史记录上登记 next_retry_at，由 job 模块周期扫描触发
        try {
            syncJobRetryService.registerRetryIfNeeded(job, history);
        } catch (Exception e) {
            logger.error("登记同步任务重试失败（不影响失败状态落库）: syncJobId={}", syncJobId, e);
        }
    }

    /**
     * 触发绑定该对象的质量任务自动检查（失败不影响主任务执行结果）。
     */
    private void triggerQualityOnSuccess(String objectType, Long objectId) {
        try {
            qualityAutoTriggerService.triggerOnSuccess(objectType, objectId);
        } catch (Exception e) {
            logger.error("质量任务自动触发失败（不影响同步结果）: type={}, objectId={}", objectType, objectId, e);
        }
    }

    /**
     * 经 alert-service 远程触发告警（Feign）。
     * 失败仅记 error 按「未发送」处理，不影响主执行流程（最终一致）。
     *
     * @return 是否发送成功（Result 拆信封；异常按 false）
     */
    private boolean fireAlert(String objectType, Long objectId, String alertType, String detail) {
        try {
            AlertFireRequest request = new AlertFireRequest();
            request.setObjectType(objectType);
            request.setObjectId(objectId);
            request.setAlertType(alertType);
            request.setDetail(detail);
            var result = alertApi.fire(request);
            return result != null && Boolean.TRUE.equals(result.data());
        } catch (Exception e) {
            logger.error("告警远程触发失败（按未发送处理，不影响任务执行结果）: type={}, objectId={}, alertType={}",
                    objectType, objectId, alertType, e);
            return false;
        }
    }

    private SyncJobHistory initHistory(Long syncJobId, String triggerType) {
        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(syncJobId);
        history.setTriggerType(triggerType);
        history.setStatus(ExecutionStatus.RUNNING.getCode());
        history.setStartTime(LocalDateTime.now());
        history.setRetryCount(0);
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(LocalDateTime.now());
        syncJobHistoryMapper.insert(history);
        return history;
    }

    private void updateJobLastExecute(SyncJob job, Long historyId) {
        job.setLastExecuteTime(LocalDateTime.now());
        job.setLastHistoryId(historyId);
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }

    private void finishHistory(SyncJobHistory history, AddaxJobService.AddaxExecutionResult result, String status) {
        LocalDateTime now = LocalDateTime.now();
        history.setStatus(status);
        history.setEndTime(now);
        if (history.getStartTime() != null) {
            history.setDurationMs(java.time.Duration.between(history.getStartTime(), now).toMillis());
        }
        if (result != null) {
            history.setSourceRows(result.readRows());
            history.setTargetRows(result.writeRows());
            if (result.tableResults() != null && !result.tableResults().isEmpty()) {
                history.setTableResults(JSON.toJSONString(result.tableResults()));
            }
            if (!result.success() && result.errorMessage() != null) {
                history.setErrorMessage(result.errorMessage());
            }
        }
        syncJobHistoryMapper.updateById(history);
    }

    /**
     * 按表批量写入 Addax 日志（table_name=源表名），消除逐行 INSERT 写放大；
     * 平台概要行（开始/成功/失败）由 insertLog 单条写入，table_name 为 NULL 归「概览」。
     */
    private void writeTableLogs(SyncJobHistory history, LogLineCounter lineCounter,
                                AddaxJobService.AddaxExecutionResult result) {
        if (result == null || result.tableResults() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AddaxJobService.TableResult tr : result.tableResults()) {
            List<String> lines = tr.logLines();
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            List<SyncJobLog> batch = new ArrayList<>(Math.min(lines.size(), LOG_BATCH_SIZE));
            for (String line : lines) {
                SyncJobLog log = new SyncJobLog();
                log.setId(IdWorker.getId());
                log.setHistoryId(history.getId());
                log.setSyncJobId(history.getSyncJobId());
                log.setLevel(detectLevel(line));
                log.setMessage(line);
                log.setLineNum(lineCounter.next());
                log.setTableName(tr.sourceTable());
                log.setCreatedAt(now);
                batch.add(log);
                if (batch.size() >= LOG_BATCH_SIZE) {
                    syncJobLogMapper.insertBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                syncJobLogMapper.insertBatch(batch);
            }
        }
    }

    private String detectLevel(String line) {
        if (line == null) {
            return "INFO";
        }
        String upper = line.toUpperCase();
        if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("FAILED") || upper.contains("失败")) {
            return "ERROR";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        return "INFO";
    }

    private void logInfo(SyncJobHistory history, LogLineCounter lineCounter, String message) {
        insertLog(history, lineCounter, "INFO", message);
    }

    private void logError(SyncJobHistory history, LogLineCounter lineCounter, String message) {
        insertLog(history, lineCounter, "ERROR", message);
    }

    private void insertLog(SyncJobHistory history, LogLineCounter lineCounter, String level, String message) {
        SyncJobLog log = new SyncJobLog();
        log.setHistoryId(history.getId());
        log.setSyncJobId(history.getSyncJobId());
        log.setLevel(level);
        log.setMessage(message);
        log.setLineNum(lineCounter.next());
        log.setCreatedAt(LocalDateTime.now());
        syncJobLogMapper.insert(log);
    }

    private int nextLineNum(Long historyId) {
        return (int) (syncJobLogMapper.selectCount(
                new QueryWrapper<SyncJobLog>().eq("history_id", historyId)) + 1L);
    }

    /**
     * 单次执行内的日志行号计数器：以 DB 现有行数+1 起始，内存自增。
     */
    private static final class LogLineCounter {
        private int next;

        LogLineCounter(int start) {
            this.next = start;
        }

        int next() {
            return next++;
        }
    }

    private void updateExecutionStatus(SyncJob job, String executionStatus) {
        job.setExecutionStatus(executionStatus);
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }
}
