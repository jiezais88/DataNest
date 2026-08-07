package com.datanest.engineering.service;

import com.datanest.common.scheduler.SchedulerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Engineering 侧同步任务调度适配器，实际调度能力下沉到 {@link SchedulerClient}。
 * 保留该类以便其它子代理仍可按原名自动注入。
 */
@Service
public class SchedulerServiceForEngineering {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerServiceForEngineering.class);
    private static final String HANDLER_NAME = "syncJobHandler";

    @Value("${datanest.engineering.worker-appname:data-nest-worker}")
    private String appName;

    private final SchedulerClient schedulerClient;

    public SchedulerServiceForEngineering(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    public Long registerJob(Long syncJobId, String name, String cronExpression, String triggerType, boolean start) {
        Long schedulerJobId = schedulerClient.registerJob(appName, HANDLER_NAME, syncJobId, name,
                cronExpression, triggerType, start, 0, 0);
        logger.info("Registered engineering sync job via SchedulerClient: name={}, schedulerJobId={}, syncJobId={}",
                name, schedulerJobId, syncJobId);
        return schedulerJobId;
    }

    public void updateJob(Long schedulerJobId, Long syncJobId, String name, String cronExpression,
                          String triggerType, boolean start) {
        schedulerClient.updateJob(schedulerJobId, appName, HANDLER_NAME, syncJobId, name,
                cronExpression, triggerType, start, 0, 0);
        logger.info("Updated engineering sync job via SchedulerClient: schedulerJobId={}, syncJobId={}", schedulerJobId, syncJobId);
    }

    public void unregisterJob(Long schedulerJobId) {
        schedulerClient.unregisterJob(schedulerJobId);
    }

    public void startJob(Long schedulerJobId) {
        schedulerClient.startJob(schedulerJobId);
    }

    public void stopJob(Long schedulerJobId) {
        schedulerClient.stopJob(schedulerJobId);
    }

    public void triggerJob(Long schedulerJobId, String executorParam) {
        schedulerClient.triggerJob(schedulerJobId, executorParam);
    }
}
