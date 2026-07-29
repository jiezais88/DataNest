package com.datanest.engineering.job;

import com.datanest.engineering.entity.SyncJob;
import com.datanest.engineering.entity.SyncJobHistory;
import com.datanest.engineering.mapper.SyncJobHistoryMapper;
import com.datanest.engineering.mapper.SyncJobMapper;
import com.datanest.engineering.service.SyncJobService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class SyncJobExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobExecutor.class);

    private final SyncJobService syncJobService;
    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;

    public SyncJobExecutor(SyncJobService syncJobService, SyncJobMapper syncJobMapper,
                           SyncJobHistoryMapper syncJobHistoryMapper) {
        this.syncJobService = syncJobService;
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
    }

    @XxlJob("syncJobHandler")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        logger.info("SyncJobHandler 开始执行，param={}", param);

        Long syncJobId = parseSyncJobId(param);
        String triggerType = parseTriggerType(param);
        Long historyId = parseHistoryId(param);

        if (syncJobId == null) {
            logger.error("SyncJobHandler 参数无效，缺少同步任务ID: param={}", param);
            XxlJobHelper.handleFail("缺少同步任务ID参数");
            return;
        }

        try {
            SyncJob job = syncJobMapper.selectById(syncJobId);
            if (job != null) {
                job.setExecutionStatus("RUNNING");
                job.setUpdatedAt(LocalDateTime.now());
                syncJobMapper.updateById(job);
            }

            syncJobService.runSyncJob(syncJobId, triggerType, historyId);
            XxlJobHelper.handleSuccess();
        } catch (Exception e) {
            logger.error("SyncJobHandler 执行异常: syncJobId={}", syncJobId, e);
            markFailed(syncJobId, historyId, "同步任务执行异常: " + e.getMessage());
            XxlJobHelper.handleFail("同步任务执行异常: " + e.getMessage());
        }
    }

    private void markFailed(Long syncJobId, Long historyId, String errorMessage) {
        try {
            SyncJob job = syncJobMapper.selectById(syncJobId);
            if (job != null) {
                job.setExecutionStatus("FAILED");
                job.setUpdatedAt(LocalDateTime.now());
                syncJobMapper.updateById(job);
            }
            if (historyId != null) {
                SyncJobHistory history = syncJobHistoryMapper.selectById(historyId);
                if (history != null) {
                    history.setStatus("FAILED");
                    history.setErrorMessage(errorMessage);
                    history.setEndTime(LocalDateTime.now());
                    if (history.getStartTime() != null && history.getDurationMs() == null) {
                        history.setDurationMs(java.time.Duration.between(history.getStartTime(), history.getEndTime()).toMillis());
                    }
                    syncJobHistoryMapper.updateById(history);
                }
            }
        } catch (Exception ex) {
            logger.error("标记任务失败状态异常: syncJobId={}, historyId={}", syncJobId, historyId, ex);
        }
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
