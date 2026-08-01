package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务失败重试（持久化模型，task-core 域）。
 * <p>
 * 替代原 engineering RetryService 的内存 ScheduledExecutorService 方案
 * （重启即丢、事务提交前登记会产生幽灵重试，且全仓库无调用方）：
 * 失败收尾时把 next_retry_at 写到失败的 sync_job_history 上（列见 V3.0.9），
 * 由 data-nest-job 的 syncJobRetryHandler 周期扫描到期记录，
 * 新建一条重试历史（parent_history_id 关联来源、retry_count+1）并触发 XXL-JOB 执行。
 * <p>
 * 重试配置取自 sync_job：retry_times（最大重试次数，0/null 不重试）、
 * retry_interval（重试间隔分钟数，默认 5 分钟，与 SyncJobCreateRequest 默认值一致）。
 */
@Service
public class SyncJobRetryService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobRetryService.class);

    /** retry_interval 未配置时的默认重试间隔（分钟） */
    private static final int DEFAULT_RETRY_INTERVAL_MINUTES = 5;

    private final SyncJobHistoryMapper syncJobHistoryMapper;

    public SyncJobRetryService(SyncJobHistoryMapper syncJobHistoryMapper) {
        this.syncJobHistoryMapper = syncJobHistoryMapper;
    }

    /**
     * 失败收尾时登记下一次重试：仅当剩余重试次数 > 0 时，
     * 在失败历史记录上写入 next_retry_at（retry_interval 分钟后）。
     * 即时单条 update 提交，不依赖外层事务。
     *
     * @param job           同步任务（含 retry_times / retry_interval / xxl_job_id 配置）
     * @param failedHistory 已标记 FAILED 的历史记录
     * @return true 表示已登记重试
     */
    public boolean registerRetryIfNeeded(SyncJob job, SyncJobHistory failedHistory) {
        if (job == null || failedHistory == null || failedHistory.getId() == null) {
            return false;
        }
        Integer retryTimes = job.getRetryTimes();
        if (retryTimes == null || retryTimes <= 0) {
            return false;
        }
        if (job.getXxlJobId() == null) {
            logger.warn("无法登记重试：同步任务未注册 XXL-JOB, syncJobId={}", job.getId());
            return false;
        }
        int retriedCount = failedHistory.getRetryCount() == null ? 0 : failedHistory.getRetryCount();
        if (retriedCount >= retryTimes) {
            logger.info("重试次数已用尽，不再登记重试: syncJobId={}, historyId={}, retryCount={}, retryTimes={}",
                    job.getId(), failedHistory.getId(), retriedCount, retryTimes);
            return false;
        }
        int intervalMinutes = job.getRetryInterval() == null || job.getRetryInterval() <= 0
                ? DEFAULT_RETRY_INTERVAL_MINUTES : job.getRetryInterval();
        LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(intervalMinutes);
        syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", nextRetryAt)
                .eq("id", failedHistory.getId()));
        logger.info("已登记同步任务重试: syncJobId={}, historyId={}, retryCount={}, nextRetryAt={}",
                job.getId(), failedHistory.getId(), retriedCount + 1, nextRetryAt);
        return true;
    }

    /**
     * 查询到期待重试的失败历史记录（供 job 模块周期 handler 扫描）。
     */
    public List<SyncJobHistory> listDueRetries(int limit) {
        return syncJobHistoryMapper.selectList(
                new QueryWrapper<SyncJobHistory>()
                        .eq("status", ExecutionStatus.FAILED.getCode())
                        .isNotNull("next_retry_at")
                        .le("next_retry_at", LocalDateTime.now())
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 认领一条到期重试并新建重试历史记录。
     * 先用条件 update 原子清空 next_retry_at（防并发扫描重复触发），
     * 认领成功后新建 status=RUNNING 的重试历史（parent_history_id 关联来源、retry_count+1）。
     *
     * @return 新建的重试历史记录；认领失败（已被其他实例处理）返回 null
     */
    public SyncJobHistory claimAndCreateRetryHistory(SyncJobHistory failedHistory) {
        int claimed = syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", null)
                .eq("id", failedHistory.getId())
                .isNotNull("next_retry_at"));
        if (claimed == 0) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        SyncJobHistory retryHistory = new SyncJobHistory();
        retryHistory.setSyncJobId(failedHistory.getSyncJobId());
        retryHistory.setTriggerType(failedHistory.getTriggerType());
        retryHistory.setParentHistoryId(failedHistory.getId());
        retryHistory.setRetryCount((failedHistory.getRetryCount() == null ? 0 : failedHistory.getRetryCount()) + 1);
        retryHistory.setStatus(ExecutionStatus.RUNNING.getCode());
        retryHistory.setStartTime(now);
        retryHistory.setSourceRows(0L);
        retryHistory.setTargetRows(0L);
        retryHistory.setCreatedAt(now);
        syncJobHistoryMapper.insert(retryHistory);
        return retryHistory;
    }

    /**
     * 仅清空 next_retry_at（任务已删除或未注册调度等无法重试的场景），
     * 避免每轮扫描重复捞到无法执行的记录。
     */
    public void clearNextRetryAt(Long historyId) {
        syncJobHistoryMapper.update(null, new UpdateWrapper<SyncJobHistory>()
                .set("next_retry_at", null)
                .eq("id", historyId));
    }

    /**
     * 重试触发失败时收尾：把刚新建的重试历史标 FAILED（不清空 retry_count，避免无限重试）。
     */
    public void markRetryHistoryFailed(SyncJobHistory retryHistory, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        retryHistory.setStatus(ExecutionStatus.FAILED.getCode());
        retryHistory.setErrorMessage(errorMessage);
        retryHistory.setEndTime(now);
        if (retryHistory.getStartTime() != null) {
            retryHistory.setDurationMs(java.time.Duration.between(retryHistory.getStartTime(), now).toMillis());
        }
        syncJobHistoryMapper.updateById(retryHistory);
    }
}
