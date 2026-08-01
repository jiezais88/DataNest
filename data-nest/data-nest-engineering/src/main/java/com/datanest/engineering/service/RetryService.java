package com.datanest.engineering.service;

import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.service.SyncJobRetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 同步任务失败重试登记（engineering 兼容入口）。
 * <p>
 * 历史背景：原实现为内存 ScheduledExecutorService 延迟触发——重启即丢、
 * 事务提交前登记会产生幽灵重试，且 scheduleRetry 全仓库无调用方。
 * 现已改为持久化模型：失败收尾时在 sync_job_history 上写入 next_retry_at/retry_count
 * （列见 V3.0.9），由 data-nest-job 的 syncJobRetryHandler 周期扫描到期记录并触发执行。
 * 真正的登记/认领逻辑在 task-core 的 {@link SyncJobRetryService}（执行链路语义归属 task-core）。
 * <p>
 * 本类仅保留以兼容现有注入点（SyncJobService 注入了本类但未调用），
 * 后续可随调用方清理一并移除。
 */
@Service
public class RetryService {

    private static final Logger logger = LoggerFactory.getLogger(RetryService.class);

    private final SyncJobRetryService syncJobRetryService;

    public RetryService(SyncJobRetryService syncJobRetryService) {
        this.syncJobRetryService = syncJobRetryService;
    }

    /**
     * 失败收尾时登记下一次重试：仅当剩余重试次数 > 0 时，
     * 在失败历史记录上写入 next_retry_at（retry_interval 分钟后）。
     * 不再创建内存延迟任务；到期的扫描与触发由 job 模块的 syncJobRetryHandler 负责。
     *
     * @param job           同步任务（含 retry_times / retry_interval / xxl_job_id 配置）
     * @param failedHistory 已标记 FAILED 的历史记录
     * @return true 表示已登记重试
     */
    public boolean scheduleRetry(SyncJob job, SyncJobHistory failedHistory) {
        boolean registered = syncJobRetryService.registerRetryIfNeeded(job, failedHistory);
        if (registered) {
            logger.info("同步任务重试已登记（持久化模型）: syncJobId={}, historyId={}",
                    job.getId(), failedHistory.getId());
        }
        return registered;
    }
}
