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
 * 同步任务失败后的延迟重试调度（方案 B）。
 * <p>
 * 每次重试都会新建一条 sync_job_history 记录，并通过 parent_history_id 关联到来源历史记录。
 * 重试计数与下次重试时间维护在历史记录上，不再写入 sync_job 主表。
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
     * @param historyId   当前失败的历史记录 ID，重试记录会关联到该记录
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

        SyncJobHistory parentHistory = syncJobHistoryMapper.selectById(historyId);
        if (parentHistory == null) {
            logger.warn("无法重试：来源历史记录不存在, syncJobId={}, historyId={}", syncJobId, historyId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        SyncJobHistory retryHistory = new SyncJobHistory();
        retryHistory.setSyncJobId(syncJobId);
        retryHistory.setTriggerType(parentHistory.getTriggerType());
        retryHistory.setParentHistoryId(historyId);
        retryHistory.setRetryCount((parentHistory.getRetryCount() == null ? 0 : parentHistory.getRetryCount()) + 1);
        retryHistory.setNextRetryAt(now.plusMinutes(delayMinutes));
        retryHistory.setStatus("RUNNING");
        retryHistory.setStartTime(now);
        retryHistory.setSourceRows(0L);
        retryHistory.setTargetRows(0L);
        retryHistory.setCreatedAt(now);
        syncJobHistoryMapper.insert(retryHistory);

        logger.info("已安排同步任务重试: syncJobId={}, parentHistoryId={}, retryHistoryId={}, delayMinutes={}, retryCount={}",
                syncJobId, historyId, retryHistory.getId(), delayMinutes, retryHistory.getRetryCount());

        scheduler.schedule(() -> {
            try {
                SyncJob refreshed = syncJobMapper.selectById(syncJobId);
                if (refreshed == null || refreshed.getXxlJobId() == null) {
                    logger.warn("重试执行时任务已不存在或未注册调度: syncJobId={}", syncJobId);
                    return;
                }
                SyncJobHistory historyToRun = syncJobHistoryMapper.selectById(retryHistory.getId());
                if (historyToRun == null) {
                    logger.warn("重试执行时历史记录已不存在: retryHistoryId={}", retryHistory.getId());
                    return;
                }
                // 将计划中的重试时间清空，表示已触发
                historyToRun.setNextRetryAt(null);
                syncJobHistoryMapper.updateById(historyToRun);

                String param = refreshed.getId() + "," + historyToRun.getTriggerType() + "," + historyToRun.getId();
                schedulerService.triggerJob(refreshed.getXxlJobId(), param);
                logger.info("已触发同步任务重试: syncJobId={}, retryHistoryId={}, param={}",
                        syncJobId, historyToRun.getId(), param);
            } catch (Exception e) {
                logger.error("同步任务重试触发失败: syncJobId={}", syncJobId, e);
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }
}
