package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSON;
import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.FinishExecutionRequest;
import com.datanest.engineering.api.dto.SyncHistoryCreateRequest;
import com.datanest.engineering.api.dto.SyncHistoryFinishRequest;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.engineering.api.dto.SyncLogAppendRequest;
import com.datanest.engineering.api.dto.SyncStatusMarkRequest;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import com.datanest.task.core.constant.AlertConstants;
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
 * <p>
 * 微服务化 3.2：sync_job / sync_job_history / sync_job_log 的读写全部经
 * {@link EngineeringSyncJobApi} 远程调用 app-engineering。
 * 容错红线：执行开始处（任务/历史读取、mark-running、init history）远程失败 fail-fast
 * （抛出让 XXL 任务失败，不跑"无登记执行"）；执行结束处（finish、日志、lastExecute）
 * 经 RemoteCalls 降级（允许丢，靠对账/reaper 兜底）。
 */
@Service
public class SyncJobExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobExecutorService.class);

    private final EngineeringSyncJobApi syncJobApi;
    private final AddaxJobService addaxJobService;
    private final MetadataRegistrationService metadataRegistrationService;
    private final SyncJobRetryService syncJobRetryService;
    private final AlertApi alertApi;
    private final GovernanceObjectApi governanceObjectApi;

    public SyncJobExecutorService(EngineeringSyncJobApi syncJobApi,
                                  AddaxJobService addaxJobService,
                                  MetadataRegistrationService metadataRegistrationService,
                                  SyncJobRetryService syncJobRetryService,
                                  AlertApi alertApi,
                                  GovernanceObjectApi governanceObjectApi) {
        this.syncJobApi = syncJobApi;
        this.addaxJobService = addaxJobService;
        this.metadataRegistrationService = metadataRegistrationService;
        this.syncJobRetryService = syncJobRetryService;
        this.alertApi = alertApi;
        this.governanceObjectApi = governanceObjectApi;
    }

    /**
     * 不使用方法级事务：Addax 外部进程执行耗时分钟~小时级，长事务会长时间占连接；
     * 且 Addax 数据已写入 Doris 后事务回滚无法撤销，反而丢失 DB 侧历史/状态。
     * 状态翻转与日志写入经 engineering 端点逐条即时生效。
     */
    public void runSyncJob(Long syncJobId, String triggerType, Long historyId) {
        SyncJobInfo job = getJobOrThrow(syncJobId);
        SyncHistoryInfo history = historyId == null ? null : getHistoryOrNull(historyId);
        if (history == null) {
            history = initHistoryOrThrow(syncJobId, triggerType);
            historyId = history.getId();
        }
        final Long currentHistoryId = historyId;

        // mark-running：执行开始处 fail-fast，不跑"无登记执行"
        markRunningOrThrow(syncJobId);
        appendLog(history, "INFO", "开始 Addax 同步执行, syncJobId=" + syncJobId + ", triggerType=" + triggerType);

        AddaxJobService.AddaxExecutionResult result = addaxJobService.execute(syncJobId, currentHistoryId);
        writeTableLogs(history, result);

        if (result.success()) {
            finishHistory(currentHistoryId, history, result, ExecutionStatus.SUCCESS.getCode());
            markStatusDegraded(syncJobId, ExecutionStatus.SUCCESS.getCode());
            updateJobLastExecute(syncJobId, currentHistoryId);
            try {
                metadataRegistrationService.register(syncJobId);
                appendLog(history, "INFO", "同步成功，已注册 Doris 元数据");
            } catch (Exception e) {
                // 同步数据已成功落 Doris，元数据注册失败不应把整个任务标 FAILED，仅记录错误
                logger.error("Doris 元数据注册失败（不影响本次同步结果）: syncJobId={}", syncJobId, e);
                appendLog(history, "ERROR", "同步成功，但 Doris 元数据注册失败: " + e.getMessage());
            }
            // Sprint 5：同步任务成功告警（按 alert_rule 配置，经 alert-service 远程触发）
            fireAlert("SYNC_JOB", syncJobId, "SUCCESS", "同步任务执行成功，写入 "
                    + result.writeRows() + " 行");
            // Sprint 8：同步任务成功后触发绑定的质量任务自动检查
            triggerQualityOnSuccess(AlertConstants.OBJECT_TYPE_SYNC_JOB, syncJobId);
            return;
        }

        // 手动停止防覆盖：watcher 强杀子进程后 Addax 以失败收尾，
        // 此处重读 history，若已被置为 TERMINATED 则不再覆盖为 FAILED，也不登记重试
        // （远程读取失败按未停止处理，与本地 selectById 返回 null 语义一致）
        SyncHistoryInfo fresh = getHistoryOrNull(currentHistoryId);
        if (fresh != null && !ExecutionStatus.RUNNING.getCode().equalsIgnoreCase(fresh.getStatus())) {
            logger.info("同步任务已被手动停止，跳过失败覆盖与重试登记: syncJobId={}, historyId={}, status={}",
                    syncJobId, currentHistoryId, fresh.getStatus());
            return;
        }

        appendLog(history, "ERROR", "Addax 执行失败: " + result.errorMessage());
        finishHistory(currentHistoryId, history, result, ExecutionStatus.FAILED.getCode());
        markStatusDegraded(syncJobId, ExecutionStatus.FAILED.getCode());
        updateJobLastExecute(syncJobId, currentHistoryId);
        appendLog(history, "ERROR", "同步任务最终失败");
        // Sprint 5：同步任务失败告警（按 alert_rule 配置，经 alert-service 远程触发）
        fireAlert("SYNC_JOB", syncJobId, "FAILURE", result.errorMessage());
        // 失败收尾：剩余重试次数 > 0 时在历史记录上登记 next_retry_at，由 job 模块周期扫描触发
        try {
            history.setStatus(ExecutionStatus.FAILED.getCode());
            syncJobRetryService.registerRetryIfNeeded(job, history);
        } catch (Exception e) {
            logger.error("登记同步任务重试失败（不影响失败状态落库）: syncJobId={}", syncJobId, e);
        }
    }

    /**
     * 触发绑定该对象的质量任务自动检查（经 governance 批量端点，RemoteCalls 降级，失败不影响主任务执行结果）。
     * 微服务化 4.2：worker 侧不再直连 QualityAutoTriggerService（本地编排），改走 Feign。
     */
    private void triggerQualityOnSuccess(String objectType, Long objectId) {
        RemoteCalls.execute("governance.quality.auto-trigger", () -> {
            QualityAutoTriggerBatchRequest request = new QualityAutoTriggerBatchRequest();
            request.setObjectType(objectType);
            request.setObjectIds(List.of(objectId));
            governanceObjectApi.qualityAutoTriggerBatch(request);
        });
    }

    /**
     * 经 alert-service 远程触发告警（Feign）。
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

    // ==================== 执行开始处：fail-fast（异常直接传播，让 XXL 任务失败） ====================

    private SyncJobInfo getJobOrThrow(Long syncJobId) {
        Result<SyncJobInfo> result = syncJobApi.getById(syncJobId);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        return result.data();
    }

    private SyncHistoryInfo getHistoryOrNull(Long historyId) {
        Result<SyncHistoryInfo> result = syncJobApi.getHistory(historyId);
        return result == null ? null : result.data();
    }

    private SyncHistoryInfo initHistoryOrThrow(Long syncJobId, String triggerType) {
        SyncHistoryCreateRequest request = new SyncHistoryCreateRequest();
        request.setSyncJobId(syncJobId);
        request.setTriggerType(triggerType);
        Result<Long> result = syncJobApi.createHistory(request);
        if (result == null || result.data() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "创建同步执行历史失败（engineering 不可达）: syncJobId=" + syncJobId);
        }
        // 本地组装后续写日志/收尾所需字段，避免再回读一次
        SyncHistoryInfo history = new SyncHistoryInfo();
        history.setId(result.data());
        history.setSyncJobId(syncJobId);
        history.setTriggerType(triggerType);
        history.setStatus(ExecutionStatus.RUNNING.getCode());
        history.setStartTime(LocalDateTime.now());
        history.setRetryCount(0);
        return history;
    }

    private void markRunningOrThrow(Long syncJobId) {
        Result<Boolean> result = syncJobApi.markRunning(syncJobId);
        if (result == null || !Boolean.TRUE.equals(result.data())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "同步任务标记 RUNNING 失败（engineering 不可达或任务不存在）: syncJobId=" + syncJobId);
        }
    }

    // ==================== 执行结束处：RemoteCalls 降级（允许丢，靠对账/reaper 兜底） ====================

    private void markStatusDegraded(Long syncJobId, String executionStatus) {
        RemoteCalls.execute("engineering.sync-job.mark-status", () -> {
            SyncStatusMarkRequest request = new SyncStatusMarkRequest();
            request.setStatus(executionStatus);
            syncJobApi.markStatus(syncJobId, request);
        });
    }

    private void updateJobLastExecute(Long syncJobId, Long historyId) {
        RemoteCalls.execute("engineering.sync-job.finish-execution", () -> {
            FinishExecutionRequest request = new FinishExecutionRequest();
            request.setHistoryId(historyId);
            request.setLastExecuteTime(LocalDateTime.now());
            syncJobApi.finishExecution(syncJobId, request);
        });
    }

    private void finishHistory(Long historyId, SyncHistoryInfo history,
                               AddaxJobService.AddaxExecutionResult result, String status) {
        RemoteCalls.execute("engineering.sync-history.finish", () -> {
            LocalDateTime now = LocalDateTime.now();
            SyncHistoryFinishRequest request = new SyncHistoryFinishRequest();
            request.setStatus(status);
            request.setEndTime(now);
            if (history.getStartTime() != null) {
                request.setDurationMs(java.time.Duration.between(history.getStartTime(), now).toMillis());
            }
            if (result != null) {
                request.setSourceRows(result.readRows());
                request.setTargetRows(result.writeRows());
                if (result.tableResults() != null && !result.tableResults().isEmpty()) {
                    request.setTableResults(JSON.toJSONString(result.tableResults()));
                }
                if (!result.success() && result.errorMessage() != null) {
                    request.setErrorMessage(result.errorMessage());
                }
            }
            syncJobApi.finishHistory(historyId, request);
        });
    }

    /**
     * 按表批量写入 Addax 日志（table_name=源表名），行号由服务端续号（logs:append），
     * 消除逐行 INSERT 与调用方 nextLineNum 读取；
     * 平台概要行（开始/成功/失败）table_name 为 NULL 归「概览」。
     */
    private void writeTableLogs(SyncHistoryInfo history, AddaxJobService.AddaxExecutionResult result) {
        if (result == null || result.tableResults() == null) {
            return;
        }
        for (AddaxJobService.TableResult tr : result.tableResults()) {
            List<String> lines = tr.logLines();
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            List<SyncLogAppendRequest.Entry> entries = new ArrayList<>(lines.size());
            for (String line : lines) {
                SyncLogAppendRequest.Entry entry = new SyncLogAppendRequest.Entry();
                entry.setContent(line);
                entry.setLevel(detectLevel(line));
                entry.setTableName(tr.sourceTable());
                entries.add(entry);
            }
            appendLogs(history.getId(), entries);
        }
    }

    private void appendLog(SyncHistoryInfo history, String level, String message) {
        SyncLogAppendRequest.Entry entry = new SyncLogAppendRequest.Entry();
        entry.setContent(message);
        entry.setLevel(level);
        appendLogs(history.getId(), List.of(entry));
    }

    private void appendLogs(Long historyId, List<SyncLogAppendRequest.Entry> entries) {
        RemoteCalls.execute("engineering.sync-log.append", () -> {
            SyncLogAppendRequest request = new SyncLogAppendRequest();
            request.setEntries(entries);
            syncJobApi.appendLogs(historyId, request);
        });
    }

    /** 与服务端续号时的缺省推断逻辑一致（显式传 level，保留原有归类） */
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
}
