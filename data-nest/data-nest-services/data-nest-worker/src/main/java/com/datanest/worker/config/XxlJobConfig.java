package com.datanest.worker.config;

import com.datanest.common.scheduler.SchedulerClient;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    private static final Logger logger = LoggerFactory.getLogger(XxlJobConfig.class);

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.accessToken}")
    private String accessToken;

    @Value("${xxl.job.executor.appname:data-nest-worker}")
    private String appName;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port:9997}")
    private int port;

    @Value("${xxl.job.executor.logpath:/data/applogs/xxl-job/jobhandler}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays:30}")
    private int logRetentionDays;

    private final SchedulerClient schedulerClient;

    public XxlJobConfig(SchedulerClient schedulerClient) {
        this.schedulerClient = schedulerClient;
    }

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        // 先确保执行器分组已存在，避免 XXL-JOB worker 启动后因分组不存在导致注册失败。
        try {
            int jobGroup = schedulerClient.ensureJobGroup(appName);
            logger.info("XXL-JOB executor group ensured before startup: appName={}, jobGroup={}", appName, jobGroup);
        } catch (Exception e) {
            logger.warn("XXL-JOB executor group ensure failed before startup, worker will retry register: appName={}", appName, e);
        }

        logger.info("Initializing XXL-JOB executor for worker: appName={}, adminAddresses={}, port={}", appName, adminAddresses, port);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
