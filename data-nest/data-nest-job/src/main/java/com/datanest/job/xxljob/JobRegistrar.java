package com.datanest.job.xxljob;

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

    private final XxlJobAdminClient xxlJobAdminClient;

    public JobRegistrar(XxlJobAdminClient xxlJobAdminClient) {
        this.xxlJobAdminClient = xxlJobAdminClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 预定义的平台定时任务：handler -> cron
        Map<String, String> platformJobs = new LinkedHashMap<>();
        platformJobs.put("dataSourceStatusRefreshHandler", "0 0/5 * * * ?");
        platformJobs.put("syncHistoryCleanupHandler", "0 0 2 * * ?");
        platformJobs.put("collectHistoryCleanupHandler", "0 30 2 * * ?");

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
        return xxlJobAdminClient.ensureJobGroup(appName);
    }

    private void ensureJob(int jobGroup, String executorHandler, String cron) {
        String jobDesc = resolveJobDesc(executorHandler);
        JsonNode existing = xxlJobAdminClient.findJobByHandler(jobGroup, executorHandler);
        if (existing != null) {
            Integer jobId = existing.path("id").asInt();
            boolean triggerStatus = existing.path("triggerStatus").asInt() == 1;
            MultiValueMap<String, String> params = xxlJobAdminClient.buildJobParams(
                    jobId, jobGroup, executorHandler, jobDesc, cron, "CRON", triggerStatus);
            xxlJobAdminClient.updateJob(params);
            logger.info("Updated platform job: executorHandler={}, jobId={}, cron={}", executorHandler, jobId, cron);
        } else {
            MultiValueMap<String, String> params = xxlJobAdminClient.buildJobParams(
                    null, jobGroup, executorHandler, jobDesc, cron, "CRON", true);
            Integer jobId = xxlJobAdminClient.addJob(params);
            logger.info("Added platform job: executorHandler={}, jobId={}, cron={}", executorHandler, jobId, cron);
        }
    }

    private String resolveJobDesc(String executorHandler) {
        return switch (executorHandler) {
            case "dataSourceStatusRefreshHandler" -> "数据源状态定时刷新";
            case "syncHistoryCleanupHandler" -> "同步任务历史清理";
            case "collectHistoryCleanupHandler" -> "采集任务历史清理";
            default -> executorHandler;
        };
    }
}
