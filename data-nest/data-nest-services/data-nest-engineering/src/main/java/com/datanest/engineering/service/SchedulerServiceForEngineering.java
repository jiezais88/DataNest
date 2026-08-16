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
    /** Sprint 11 F3 方案 A：DAG cron 触发器 handler（job 侧 DagScheduledTriggerHandler） */
    private static final String DAG_CRON_HANDLER_NAME = "dagScheduledTriggerHandler";

    @Value("${datanest.engineering.worker-appname:data-nest-worker}")
    private String appName;

    @Value("${datanest.engineering.job-appname:data-nest-job}")
    private String jobAppName;

    private final SchedulerClient schedulerClient;

    public SchedulerServiceForEngineering(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    // ==================== DAG cron（Sprint 11 F3 方案 A） ====================

    /** 注册 DAG cron job（cron 到点调 /internal/dag/scheduled-trigger，做队列容量判定） */
    public Long registerDagCronJob(Long dagId, String dagName, String cronExpression, boolean start) {
        Long schedulerJobId = schedulerClient.registerJob(jobAppName, DAG_CRON_HANDLER_NAME, dagId, dagName,
                cronExpression, "CRON", start, 0, 0);
        logger.info("Registered DAG cron job: dagId={}, schedulerJobId={}, cron={}, start={}",
                dagId, schedulerJobId, cronExpression, start);
        return schedulerJobId;
    }

    /** 更新 DAG cron job（cron/启停变更后全量覆盖） */
    public void updateDagCronJob(Long schedulerJobId, Long dagId, String dagName, String cronExpression, boolean start) {
        schedulerClient.updateJob(schedulerJobId, jobAppName, DAG_CRON_HANDLER_NAME, dagId, dagName,
                cronExpression, "CRON", start, 0, 0);
        logger.info("Updated DAG cron job: schedulerJobId={}, dagId={}, cron={}, start={}",
                schedulerJobId, dagId, cronExpression, start);
    }

    /** 注销 DAG cron job（DAG 删除或转非 CRON 时） */
    public void unregisterDagCronJob(Long schedulerJobId) {
        if (schedulerJobId != null) {
            schedulerClient.unregisterJob(schedulerJobId);
        }
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
