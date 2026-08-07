package com.datanest.task.core.job;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.SyncHistoryFinishRequest;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.engineering.api.dto.SyncStatusMarkRequest;
import com.datanest.task.core.service.SyncJobExecutorService;
import com.datanest.task.core.service.SyncJobRetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 同步任务执行核心，供 data-nest-worker 调用。
 * 不携带 XXL-JOB 注解，handler 定义在 worker 模块。
 * <p>
 * 微服务化 3.2：sync_job / sync_job_history 的读写经 {@link EngineeringSyncJobApi}
 * 远程调用 app-engineering。执行开始处（mark-running）fail-fast；
 * 异常收尾（markFailed 内的状态翻转/重试登记）经 RemoteCalls 降级，允许丢（reaper 兜底）。
 */
@Service
public class SyncJobExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobExecutor.class);

    private final SyncJobExecutorService syncJobExecutorService;
    private final EngineeringSyncJobApi syncJobApi;
    private final SyncJobRetryService syncJobRetryService;

    public SyncJobExecutor(SyncJobExecutorService syncJobExecutorService,
                           EngineeringSyncJobApi syncJobApi,
                           SyncJobRetryService syncJobRetryService) {
        this.syncJobExecutorService = syncJobExecutorService;
        this.syncJobApi = syncJobApi;
        this.syncJobRetryService = syncJobRetryService;
    }

    public void execute(String param) {
        logger.info("SyncJobHandler 开始执行，param={}", param);

        Long syncJobId = parseSyncJobId(param);
        String triggerType = parseTriggerType(param);
        Long historyId = parseHistoryId(param);

        if (syncJobId == null) {
            logger.error("SyncJobHandler 参数无效，缺少同步任务ID: param={}", param);
            throw new IllegalArgumentException("缺少同步任务ID参数");
        }

        try {
            // mark-running：执行开始处 fail-fast，不跑"无登记执行"
            markRunningOrThrow(syncJobId);
            syncJobExecutorService.runSyncJob(syncJobId, triggerType, historyId);
        } catch (Exception e) {
            logger.error("SyncJobHandler 执行异常: syncJobId={}", syncJobId, e);
            markFailed(syncJobId, historyId, "同步任务执行异常: " + e.getMessage());
            throw e;
        }
    }

    private void markRunningOrThrow(Long syncJobId) {
        Result<Boolean> result = syncJobApi.markRunning(syncJobId);
        if (result == null || !Boolean.TRUE.equals(result.data())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "同步任务标记 RUNNING 失败（engineering 不可达或任务不存在）: syncJobId=" + syncJobId);
        }
    }

    private void markFailed(Long syncJobId, Long historyId, String errorMessage) {
        try {
            // 手动停止防覆盖：history 已被置为 TERMINATED（终态）时，
            // 不再覆盖为 FAILED，也不登记重试，由停止方负责收尾
            SyncHistoryInfo history = historyId == null ? null : getHistoryOrNull(historyId);
            if (history != null && !"RUNNING".equalsIgnoreCase(history.getStatus())) {
                logger.info("同步任务已被手动停止，跳过失败标记与重试登记: syncJobId={}, historyId={}, status={}",
                        syncJobId, historyId, history.getStatus());
                return;
            }
            SyncJobInfo job = getJobOrNull(syncJobId);
            if (job != null) {
                RemoteCalls.execute("engineering.sync-job.mark-status", () -> {
                    SyncStatusMarkRequest request = new SyncStatusMarkRequest();
                    request.setStatus("FAILED");
                    syncJobApi.markStatus(syncJobId, request);
                });
            }
            if (history != null) {
                RemoteCalls.execute("engineering.sync-history.finish", () -> {
                    SyncHistoryFinishRequest request = new SyncHistoryFinishRequest();
                    request.setStatus("FAILED");
                    request.setErrorMessage(errorMessage);
                    request.setEndTime(LocalDateTime.now());
                    syncJobApi.finishHistory(historyId, request);
                });
                // 异常失败收尾：剩余重试次数 > 0 时登记持久化重试
                history.setStatus("FAILED");
                syncJobRetryService.registerRetryIfNeeded(job, history);
            }
        } catch (Exception ex) {
            logger.error("标记任务失败状态异常: syncJobId={}, historyId={}", syncJobId, historyId, ex);
        }
    }

    private SyncJobInfo getJobOrNull(Long syncJobId) {
        Result<SyncJobInfo> result = syncJobApi.getById(syncJobId);
        return result == null ? null : result.data();
    }

    private SyncHistoryInfo getHistoryOrNull(Long historyId) {
        Result<SyncHistoryInfo> result = syncJobApi.getHistory(historyId);
        return result == null ? null : result.data();
    }

    private Long parseSyncJobId(String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(param.split(",")[0].trim());
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private String parseTriggerType(String param) {
        if (param == null || param.isBlank()) {
            return "CRON";
        }
        String[] parts = param.split(",");
        return parts.length > 1 ? parts[1].trim() : "CRON";
    }

    private Long parseHistoryId(String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        String[] parts = param.split(",");
        if (parts.length > 2) {
            try {
                return Long.valueOf(parts[2].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
