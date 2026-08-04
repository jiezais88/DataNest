package com.datanest.job.xxljob;

import com.datanest.common.scheduler.SchedulerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时通过 XXL-JOB Admin REST API 注册/更新平台定时任务。
 */
@Component
public class JobRegistrar implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(JobRegistrar.class);

    @Value("${datanest.job.xxl-job.job-group-id:0}")
    private int configuredJobGroupId;

    @Value("${xxl.job.executor.appname:data-nest-job}")
    private String appName;

    @Value("${datanest.job.dag-sync.cron:0/30 * * * * ?}")
    private String dagSyncCron;

    @Value("${datanest.job.dag-timeout-alert.cron:0 * * * * ?}")
    private String dagTimeoutAlertCron;

    private final SchedulerClient schedulerClient;

    public JobRegistrar(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 预定义的平台定时任务：handler -> cron
        Map<String, String> platformJobs = new LinkedHashMap<>();
        platformJobs.put("dataSourceStatusRefreshHandler", "0 0/5 * * * ?");
        platformJobs.put("syncHistoryCleanupHandler", "0 0 2 * * ?");
        platformJobs.put("collectHistoryCleanupHandler", "0 30 2 * * ?");
        // Sprint 3：DS 任务实例状态同步（默认 30s 兜底；handler 内有自适应触发，仍有 RUNNING 时缩短到 5s）
        platformJobs.put("dagExecutionSyncHandler", dagSyncCron);
        // Sprint 3：DAG 执行历史清理（每天凌晨 3 点，保留 30 天）
        platformJobs.put("dagExecutionHistoryCleanupHandler", "0 0 3 * * ?");
        // Sprint 5：血缘记录清理（默认每天凌晨 3 点 30 分，保留 90 天）
        platformJobs.put("lineageRecordCleanupHandler", "0 30 3 * * ?");
        // Sprint 5：告警发送历史清理（默认每天凌晨 4 点，保留 90 天）
        platformJobs.put("alertHistoryCleanupHandler", "0 0 4 * * ?");
        // 卡死 RUNNING 收割（每小时，阈值 datanest.task.stuck-running-timeout-minutes 默认 120 分钟）
        platformJobs.put("stuckExecutionReaperHandler", "0 0 * * * ?");
        // 同步任务持久化重试扫描（每小时，实际触发时间 = next_retry_at 之后的第一个整点扫描周期）
        platformJobs.put("syncJobRetryHandler", "0 10 * * * ?");
        // Sprint 4：DAG 节点超时告警扫描（默认每分钟）
        platformJobs.put("dagNodeTimeoutAlertHandler", dagTimeoutAlertCron);

        int jobGroup = resolveJobGroup();
        logger.info("Ensuring platform jobs registered in XXL-JOB, jobGroup={}", jobGroup);

        for (Map.Entry<String, String> entry : platformJobs.entrySet()) {
            String executorHandler = entry.getKey();
            String cron = entry.getValue();
            try {
                ensureJob(jobGroup, executorHandler, cron);
            } catch (Exception e) {
                logger.error("Failed to ensure platform job: executorHandler={}", executorHandler, e);
            }
        }
    }

    private int resolveJobGroup() {
        if (configuredJobGroupId > 0) {
            return configuredJobGroupId;
        }
        return schedulerClient.ensureJobGroup(appName);
    }

    private void ensureJob(int jobGroup, String executorHandler, String cron) {
        String jobDesc = resolveJobDesc(executorHandler);
        JsonNode existing = schedulerClient.findJobByHandler(jobGroup, executorHandler);
        if (existing != null) {
            Integer jobId = existing.path("id").asInt();
            boolean triggerStatus = existing.path("triggerStatus").asInt() == 1;
            MultiValueMap<String, String> params = schedulerClient.buildPlatformJobParams(
                    jobId, jobGroup, executorHandler, jobDesc, cron, triggerStatus);
            schedulerClient.updateJob(params);
            logger.info("Updated platform job: executorHandler={}, jobId={}, cron={}", executorHandler, jobId, cron);
        } else {
            MultiValueMap<String, String> params = schedulerClient.buildPlatformJobParams(
                    null, jobGroup, executorHandler, jobDesc, cron, true);
            Integer jobId = schedulerClient.addJob(params);
            logger.info("Added platform job: executorHandler={}, jobId={}, cron={}", executorHandler, jobId, cron);
        }
    }

    private String resolveJobDesc(String executorHandler) {
        return switch (executorHandler) {
            case "dataSourceStatusRefreshHandler" -> "数据源状态定时刷新";
            case "syncHistoryCleanupHandler" -> "同步任务历史清理";
            case "collectHistoryCleanupHandler" -> "采集任务历史清理";
            case "dagExecutionSyncHandler" -> "DAG 执行状态同步";
            case "dagExecutionHistoryCleanupHandler" -> "DAG 执行历史清理";
            case "lineageRecordCleanupHandler" -> "血缘记录清理";
            case "alertHistoryCleanupHandler" -> "告警发送历史清理";
            case "stuckExecutionReaperHandler" -> "卡死 RUNNING 执行收割";
            case "syncJobRetryHandler" -> "同步任务失败重试扫描";
            case "dagNodeTimeoutAlertHandler" -> "DAG 节点超时告警扫描";
            default -> executorHandler;
        };
    }
}
