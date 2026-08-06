package com.datanest.worker.job;

import com.datanest.task.core.job.SyncJobExecutor;
import com.datanest.task.core.service.CollectExecutor;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * data-nest-worker 的 XXL-JOB 任务入口，负责同步任务与元数据采集任务的实际执行。
 */
@Component
public class WorkerJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(WorkerJobHandler.class);

    private final SyncJobExecutor syncJobExecutor;
    private final CollectExecutor collectExecutor;

    public WorkerJobHandler(SyncJobExecutor syncJobExecutor, CollectExecutor collectExecutor) {
        this.syncJobExecutor = syncJobExecutor;
        this.collectExecutor = collectExecutor;
    }

    @XxlJob("syncJobHandler")
    public void syncJobHandler() {
        String param = XxlJobHelper.getJobParam();
        try {
            syncJobExecutor.execute(param);
            XxlJobHelper.handleSuccess();
        } catch (Exception e) {
            logger.error("syncJobHandler 执行失败: param={}", param, e);
            XxlJobHelper.handleFail("同步任务执行失败: " + e.getMessage());
        }
    }

    @XxlJob("collectTaskHandler")
    public void collectTaskHandler() {
        String param = XxlJobHelper.getJobParam();
        try {
            collectExecutor.execute(param);
            XxlJobHelper.handleSuccess();
        } catch (Exception e) {
            logger.error("collectTaskHandler 执行失败: param={}", param, e);
            XxlJobHelper.handleFail("采集任务执行失败: " + e.getMessage());
        }
    }
}
