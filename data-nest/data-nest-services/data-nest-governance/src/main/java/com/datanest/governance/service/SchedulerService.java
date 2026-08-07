package com.datanest.governance.service;

import com.datanest.common.scheduler.SchedulerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Governance 侧采集任务调度适配器，实际调度能力下沉到 {@link SchedulerClient}
 * （底层为 PowerJob OpenAPI，executorHandler 即 processorInfo 路由名）。
 */
@Service
public class SchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerService.class);
    private static final String HANDLER_NAME = "collectTaskHandler";

    @Value("${datanest.governance.worker-appname:data-nest-worker}")
    private String appName;

    private final SchedulerClient schedulerClient;

    public SchedulerService(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    public Long registerJob(Long taskId, String name, String cronExpression, String scheduleType, boolean start) {
        Long schedulerJobId = schedulerClient.registerJob(appName, HANDLER_NAME, taskId, name,
                cronExpression, scheduleType, start, 0, 0);
        logger.info("Registered governance collect job via SchedulerClient: name={}, schedulerJobId={}, taskId={}",
                name, schedulerJobId, taskId);
        return schedulerJobId;
    }

    public void updateJob(Long jobId, Long taskId, String name, String cronExpression,
                          String scheduleType, boolean start) {
        schedulerClient.updateJob(jobId, appName, HANDLER_NAME, taskId, name,
                cronExpression, scheduleType, start, 0, 0);
        logger.info("Updated governance collect job via SchedulerClient: jobId={}, taskId={}", jobId, taskId);
    }

    public void unregisterJob(Long jobId) {
        schedulerClient.unregisterJob(jobId);
    }

    public void startJob(Long jobId) {
        schedulerClient.startJob(jobId);
    }

    public void stopJob(Long jobId) {
        schedulerClient.stopJob(jobId);
    }

    public void triggerJob(Long jobId, String executorParam) {
        schedulerClient.triggerJob(jobId, executorParam);
    }
}
