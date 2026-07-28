package com.datanest.engineering.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.entity.SyncJob;
import com.datanest.engineering.entity.SyncJobHistory;
import com.datanest.engineering.mapper.SyncJobHistoryMapper;
import com.datanest.engineering.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 同步任务失败后的延迟重试调度。
 * <p>
 * 使用单线程 ScheduledExecutorService，避免在 XXL-JOB 执行线程中 Thread.sleep 阻塞。
 */
@Service
public class RetryService {

    private static final Logger logger = LoggerFactory.getLogger(RetryService.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sync-job-retry");
        t.setDaemon(true);
        return t;
    });

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SchedulerServiceForEngineering schedulerService;

    public RetryService(SyncJobMapper syncJobMapper,
                        SyncJobHistoryMapper syncJobHistoryMapper,
                        SchedulerServiceForEngineering schedulerService) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.schedulerService = schedulerService;
    }

    /**
     * 安排一次延迟重试。
     *
     * @param syncJobId   同步任务 ID
     * @param historyId   当前失败的历史 ID（仅用于日志关联，重试会创建新历史行）
     * @param delayMinutes 延迟分钟数
     */
    @Transactional
    public void scheduleRetry(Long syncJobId, Long historyId, int delayMinutes) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        if (job.getXxlJobId() == null) {
            logger.warn("无法重试：同步任务未注册 XXL-JOB, syncJobId={}", syncJobId);
            return;
        }

        int currentRetry = job.getRetryCount() == null ? 0 : job.getRetryCount();
        job.setRetryCount(currentRetry + 1);
        job.setNextRetryAt(LocalDateTime.now().plusMinutes(delayMinutes));
        syncJobMapper.updateById(job);

        logger.info("已安排同步任务重试: syncJobId={}, historyId={}, delayMinutes={}, retryCount={}",
                syncJobId, historyId, delayMinutes, job.getRetryCount());

        scheduler.schedule(() -> {
            try {
                SyncJob refreshed = syncJobMapper.selectById(syncJobId);
                if (refreshed == null || refreshed.getXxlJobId() == null) {
                    logger.warn("重试执行时任务已不存在或未注册调度: syncJobId={}", syncJobId);
                    return;
                }
                SyncJobHistory retryHistory = createRetryHistory(syncJobId, refreshed.getTriggerType());
                String param = refreshed.getId() + "," + refreshed.getTriggerType() + "," + retryHistory.getId();
                schedulerService.triggerJob(refreshed.getXxlJobId(), param);
                logger.info("已触发同步任务重试: syncJobId={}, historyId={}, param={}",
                        syncJobId, retryHistory.getId(), param);
            } catch (Exception e) {
                logger.error("同步任务重试触发失败: syncJobId={}", syncJobId, e);
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }

    private SyncJobHistory createRetryHistory(Long syncJobId, String triggerType) {
        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(syncJobId);
        history.setTriggerType(triggerType);
        history.setStatus("RUNNING");
        history.setStartTime(LocalDateTime.now());
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(LocalDateTime.now());
        syncJobHistoryMapper.insert(history);
        return history;
    }
}
