package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量同步任务执行核心，供 data-nest-worker 与 engineering 同进程调用。
 * 不处理 XXL-JOB 调度注册与重试调度，仅负责任务的单次执行。
 */
@Service
public class SyncJobExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobExecutorService.class);

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;
    private final AddaxJobService addaxJobService;
    private final MetadataRegistrationService metadataRegistrationService;

    public SyncJobExecutorService(SyncJobMapper syncJobMapper,
                                  SyncJobHistoryMapper syncJobHistoryMapper,
                                  SyncJobLogMapper syncJobLogMapper,
                                  AddaxJobService addaxJobService,
                                  MetadataRegistrationService metadataRegistrationService) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
        this.addaxJobService = addaxJobService;
        this.metadataRegistrationService = metadataRegistrationService;
    }

    @Transactional
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

        updateExecutionStatus(job, ExecutionStatus.RUNNING.getCode());
        logInfo(history, "开始 Addax 同步执行, syncJobId=" + syncJobId + ", triggerType=" + triggerType);

        AddaxJobService.AddaxExecutionResult result = addaxJobService.execute(syncJobId);
        writeLogLines(history, result.logLines());

        if (result.success()) {
            finishHistory(history, result, ExecutionStatus.SUCCESS.getCode());
            updateExecutionStatus(job, ExecutionStatus.SUCCESS.getCode());
            updateJobLastExecute(job, history.getId());
            metadataRegistrationService.register(syncJobId);
            logInfo(history, "同步成功，已注册 Doris 元数据");
            return;
        }

        logError(history, "Addax 执行失败: " + result.errorMessage());
        finishHistory(history, result, ExecutionStatus.FAILED.getCode());
        updateExecutionStatus(job, ExecutionStatus.FAILED.getCode());
        updateJobLastExecute(job, history.getId());
        logError(history, "同步任务最终失败");
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
            if (!result.success() && result.errorMessage() != null) {
                history.setErrorMessage(result.errorMessage());
            }
        }
        syncJobHistoryMapper.updateById(history);
    }

    private void writeLogLines(SyncJobHistory history, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int lineNum = nextLineNum(history.getId());
        LocalDateTime now = LocalDateTime.now();
        for (String line : lines) {
            SyncJobLog log = new SyncJobLog();
            log.setHistoryId(history.getId());
            log.setSyncJobId(history.getSyncJobId());
            log.setLevel(detectLevel(line));
            log.setMessage(line);
            log.setLineNum(lineNum++);
            log.setCreatedAt(now);
            syncJobLogMapper.insert(log);
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

    private void logInfo(SyncJobHistory history, String message) {
        insertLog(history, "INFO", message);
    }

    private void logError(SyncJobHistory history, String message) {
        insertLog(history, "ERROR", message);
    }

    private void insertLog(SyncJobHistory history, String level, String message) {
        SyncJobLog log = new SyncJobLog();
        log.setHistoryId(history.getId());
        log.setSyncJobId(history.getSyncJobId());
        log.setLevel(level);
        log.setMessage(message);
        log.setLineNum(nextLineNum(history.getId()));
        log.setCreatedAt(LocalDateTime.now());
        syncJobLogMapper.insert(log);
    }

    private int nextLineNum(Long historyId) {
        return (int) (syncJobLogMapper.selectCount(
                new QueryWrapper<SyncJobLog>().eq("history_id", historyId)) + 1L);
    }

    private void updateExecutionStatus(SyncJob job, String executionStatus) {
        job.setExecutionStatus(executionStatus);
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);
    }
}
