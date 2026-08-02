package com.datanest.task.core.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.scheduler.SchedulerClient;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 同步任务触发服务。
 * Sprint 4 下沉到 task-core，供 engineering 与 worker 共用。
 * 负责创建 sync_job_history 并触发 XXL-JOB executor 执行。
 */
@Service
public class SyncJobTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobTriggerService.class);
    private static final String HANDLER_NAME = "syncJobHandler";

    @Value("${datanest.engineering.worker-appname:data-nest-worker}")
    private String appName;

    private final SyncJobMapper syncJobMapper;
    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SchedulerClient schedulerClient;

    public SyncJobTriggerService(SyncJobMapper syncJobMapper,
                                 SyncJobHistoryMapper syncJobHistoryMapper,
                                 SchedulerClient schedulerClient) {
        this.syncJobMapper = syncJobMapper;
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 触发同步任务执行，返回生成的 sync_job_history.id。
     * DAG 回调场景通过 triggerType 区分来源。
     */
    public Long triggerSyncJob(Long syncJobId, String triggerType) {
        return triggerSyncJob(syncJobId, triggerType, null);
    }

    /**
     * 触发同步任务执行并记录来源 DAG 执行实例。
     */
    public Long triggerSyncJob(Long syncJobId, String triggerType, Long dagExecutionId) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }
        // 手动任务在创建时不会注册 XXL-JOB，执行前按需注册
        if (job.getXxlJobId() == null) {
            Integer jobId = schedulerClient.registerJob(appName, HANDLER_NAME, job.getId(), job.getName(),
                    job.getCronExpression(), job.getTriggerType(), false, 0, 0);
            job.setXxlJobId(jobId);
            syncJobMapper.updateById(job);
        }
        job.setExecutionStatus("RUNNING");
        job.setUpdatedAt(LocalDateTime.now());
        syncJobMapper.updateById(job);

        SyncJobHistory history = new SyncJobHistory();
        history.setSyncJobId(syncJobId);
        history.setDagExecutionId(dagExecutionId);
        history.setTriggerType(triggerType);
        history.setStatus("RUNNING");
        history.setStartTime(LocalDateTime.now());
        history.setRetryCount(0);
        history.setSourceRows(0L);
        history.setTargetRows(0L);
        history.setCreatedAt(LocalDateTime.now());
        syncJobHistoryMapper.insert(history);

        String param = syncJobId + "," + triggerType + "," + history.getId();
        schedulerClient.triggerJob(job.getXxlJobId(), param);
        logger.info("已触发同步任务执行: syncJobId={}, historyId={}, triggerType={}, param={}",
                syncJobId, history.getId(), triggerType, param);
        return history.getId();
    }
}
