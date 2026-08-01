package com.datanest.job.handler;

import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.SyncJobMapper;
import com.datanest.task.core.service.SyncJobRetryService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 同步任务持久化重试触发任务。
 * <p>
 * 扫描 sync_job_history 中 status=FAILED 且 next_retry_at 到期的记录
 * （由 task-core SyncJobRetryService.registerRetryIfNeeded 在失败收尾时登记），
 * 新建一条重试历史（parent_history_id 关联来源、retry_count+1）并通过 XXL-JOB 触发执行。
 * 替代原 engineering RetryService 的内存 ScheduledExecutorService 方案（重启即丢）。
 */
@Component
public class SyncJobRetryHandler {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobRetryHandler.class);

    /** 单轮最多处理的重试记录数，避免积压时单轮执行过久 */
    private static final int BATCH_LIMIT = 50;

    private final SyncJobRetryService syncJobRetryService;
    private final SyncJobMapper syncJobMapper;
    private final SchedulerClient schedulerClient;

    public SyncJobRetryHandler(SyncJobRetryService syncJobRetryService,
                               SyncJobMapper syncJobMapper,
                               SchedulerClient schedulerClient) {
        this.syncJobRetryService = syncJobRetryService;
        this.syncJobMapper = syncJobMapper;
        this.schedulerClient = schedulerClient;
    }

    @XxlJob("syncJobRetryHandler")
    public void retry() {
        int triggered = 0;
        int failed = 0;
        try {
            List<SyncJobHistory> dueRetries = syncJobRetryService.listDueRetries(BATCH_LIMIT);
            for (SyncJobHistory failedHistory : dueRetries) {
                try {
                    if (triggerOne(failedHistory)) {
                        triggered++;
                    }
                } catch (Exception e) {
                    failed++;
                    logger.error("处理到期重试失败: historyId={}, syncJobId={}",
                            failedHistory.getId(), failedHistory.getSyncJobId(), e);
                }
            }
            logger.info("同步任务重试扫描完成: due={}, triggered={}, failed={}", dueRetries.size(), triggered, failed);
            XxlJobHelper.handleSuccess("due=" + dueRetries.size() + ", triggered=" + triggered + ", failed=" + failed);
        } catch (Exception e) {
            logger.error("同步任务重试扫描失败", e);
            XxlJobHelper.handleFail("同步任务重试扫描失败: " + e.getMessage());
        }
    }

    /**
     * 处理一条到期重试：认领并新建重试历史后触发 XXL-JOB。
     *
     * @return true 表示已触发
     */
    private boolean triggerOne(SyncJobHistory failedHistory) {
        SyncJob job = syncJobMapper.selectById(failedHistory.getSyncJobId());
        if (job == null || job.getXxlJobId() == null) {
            logger.warn("到期重试跳过：任务不存在或未注册 XXL-JOB，清空 next_retry_at: historyId={}, syncJobId={}",
                    failedHistory.getId(), failedHistory.getSyncJobId());
            // 仅清空 next_retry_at，避免每轮重复扫描到无法执行的记录
            syncJobRetryService.clearNextRetryAt(failedHistory.getId());
            return false;
        }

        SyncJobHistory retryHistory = syncJobRetryService.claimAndCreateRetryHistory(failedHistory);
        if (retryHistory == null) {
            // 已被其他实例认领
            return false;
        }

        String param = job.getId() + "," + retryHistory.getTriggerType() + "," + retryHistory.getId();
        try {
            schedulerClient.triggerJob(job.getXxlJobId(), param);
            logger.info("已触发同步任务重试: syncJobId={}, parentHistoryId={}, retryHistoryId={}, retryCount={}",
                    job.getId(), failedHistory.getId(), retryHistory.getId(), retryHistory.getRetryCount());
            return true;
        } catch (Exception e) {
            // 触发失败：把新建的重试历史标 FAILED，避免残留假 RUNNING
            logger.error("同步任务重试触发失败: syncJobId={}, retryHistoryId={}", job.getId(), retryHistory.getId(), e);
            syncJobRetryService.markRetryHistoryFailed(retryHistory, "重试触发失败: " + e.getMessage());
            throw e;
        }
    }
}
