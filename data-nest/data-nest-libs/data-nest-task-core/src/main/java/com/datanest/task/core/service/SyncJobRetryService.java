package com.datanest.task.core.service;

import com.datanest.common.constant.ExecutionStatus;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.HistoryMarkFailedRequest;
import com.datanest.engineering.api.dto.RegisterRetryRequest;
import com.datanest.engineering.api.dto.SyncHistoryCreateRequest;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
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
 * 微服务化 3.2：sync_job_history 的读写全部经 {@link EngineeringSyncJobApi}
 * 远程调用 app-engineering（登记/到期查询/原子认领/重试历史收尾端点）。
 * <p>
 * 重试配置取自 sync_job：retry_times（最大重试次数，0/null 不重试）、
 * retry_interval（重试间隔分钟数，默认 5 分钟，与 SyncJobCreateRequest 默认值一致）。
 */
@Service
public class SyncJobRetryService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobRetryService.class);

    /** retry_interval 未配置时的默认重试间隔（分钟） */
    private static final int DEFAULT_RETRY_INTERVAL_MINUTES = 5;

    private final EngineeringSyncJobApi syncJobApi;

    public SyncJobRetryService(EngineeringSyncJobApi syncJobApi) {
        this.syncJobApi = syncJobApi;
    }

    /**
     * 失败收尾时登记下一次重试：仅当剩余重试次数 > 0 时，
     * 在失败历史记录上写入 next_retry_at（retry_interval 分钟后）。
     *
     * @param job           同步任务（含 retry_times / retry_interval / scheduler_job_id 配置）
     * @param failedHistory 已标记 FAILED 的历史记录
     * @return true 表示已登记重试
     */
    public boolean registerRetryIfNeeded(SyncJobInfo job, SyncHistoryInfo failedHistory) {
        if (job == null || failedHistory == null || failedHistory.getId() == null) {
            return false;
        }
        Integer retryTimes = job.getRetryTimes();
        if (retryTimes == null || retryTimes <= 0) {
            return false;
        }
        if (job.getSchedulerJobId() == null) {
            logger.warn("无法登记重试：同步任务未注册调度任务（PowerJob）, syncJobId={}", job.getId());
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
        RegisterRetryRequest request = new RegisterRetryRequest();
        request.setNextRetryAt(nextRetryAt);
        syncJobApi.registerRetry(failedHistory.getId(), request);
        logger.info("已登记同步任务重试: syncJobId={}, historyId={}, retryCount={}, nextRetryAt={}",
                job.getId(), failedHistory.getId(), retriedCount + 1, nextRetryAt);
        return true;
    }

    /**
     * 查询到期待重试的失败历史记录（供 job 模块周期 handler 扫描）。
     */
    public List<SyncHistoryInfo> listDueRetries(int limit) {
        Result<List<SyncHistoryInfo>> result = syncJobApi.dueRetries(Math.max(1, limit));
        return result == null || result.data() == null ? List.of() : result.data();
    }

    /**
     * 认领一条到期重试并新建重试历史记录。
     * 先经 claim-retry 端点原子清空 next_retry_at（防并发扫描重复触发），
     * 认领成功后新建 status=RUNNING 的重试历史（parent_history_id 关联来源、retry_count+1）。
     *
     * @return 新建的重试历史记录；认领失败（返回 false 或远程异常，已被其他实例处理/下轮再来）返回 null
     */
    public SyncHistoryInfo claimAndCreateRetryHistory(SyncHistoryInfo failedHistory) {
        Result<Boolean> claimResult = syncJobApi.claimRetry(failedHistory.getId());
        if (claimResult == null || !Boolean.TRUE.equals(claimResult.data())) {
            return null;
        }
        int retryCount = (failedHistory.getRetryCount() == null ? 0 : failedHistory.getRetryCount()) + 1;
        SyncHistoryCreateRequest request = new SyncHistoryCreateRequest();
        request.setSyncJobId(failedHistory.getSyncJobId());
        request.setTriggerType(failedHistory.getTriggerType());
        request.setParentHistoryId(failedHistory.getId());
        request.setRetryCount(retryCount);
        Result<Long> createResult = syncJobApi.createHistory(request);
        if (createResult == null || createResult.data() == null) {
            // 已认领但建历史失败：不假认领，记 error 由人工/对账兜底（next_retry_at 已清空，不会重复触发）
            logger.error("重试认领后创建重试历史失败（本次重试丢失）: syncJobId={}, parentHistoryId={}",
                    failedHistory.getSyncJobId(), failedHistory.getId());
            return null;
        }
        // 本地组装触发 XXL 所需字段，避免再回读一次
        SyncHistoryInfo retryHistory = new SyncHistoryInfo();
        retryHistory.setId(createResult.data());
        retryHistory.setSyncJobId(failedHistory.getSyncJobId());
        retryHistory.setTriggerType(failedHistory.getTriggerType());
        retryHistory.setParentHistoryId(failedHistory.getId());
        retryHistory.setRetryCount(retryCount);
        retryHistory.setStatus(ExecutionStatus.RUNNING.getCode());
        retryHistory.setStartTime(LocalDateTime.now());
        return retryHistory;
    }

    /**
     * 仅清空 next_retry_at（任务已删除或未注册调度等无法重试的场景），
     * 避免每轮扫描重复捞到无法执行的记录。降级处理：失败下轮再清。
     */
    public void clearNextRetryAt(Long historyId) {
        RemoteCalls.execute("engineering.sync-history.clear-retry",
                () -> syncJobApi.claimRetry(historyId));
    }

    /**
     * 重试触发失败时收尾：把刚新建的重试历史标 FAILED（不清空 retry_count，避免无限重试）。
     * 降级处理：失败由 reaper 兜底收割。
     */
    public void markRetryHistoryFailed(SyncHistoryInfo retryHistory, String errorMessage) {
        RemoteCalls.execute("engineering.sync-history.mark-failed", () -> {
            HistoryMarkFailedRequest request = new HistoryMarkFailedRequest();
            request.setErrorMessage(errorMessage);
            syncJobApi.markHistoryFailed(retryHistory.getId(), request);
        });
    }
}
