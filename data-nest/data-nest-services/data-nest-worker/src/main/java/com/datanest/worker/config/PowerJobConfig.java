package com.datanest.worker.config;

import com.datanest.common.scheduler.PowerJobWorkerSupport;
import com.datanest.worker.job.PlatformJobProcessorFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.powerjob.worker.PowerJobSpringWorker;
import tech.powerjob.worker.autoconfigure.PowerJobProperties;

import java.util.List;

/**
 * PowerJob Worker 配置：在 starter 自动装配的基础上追加自定义
 * {@link PlatformJobProcessorFactory}（按 processorInfo 路由平台 handler）。
 * <p>
 * starter 的 {@code PowerJobAutoConfiguration#initPowerJob} 带
 * {@code @ConditionalOnMissingBean}，本类显式声明 PowerJobSpringWorker Bean 后自动装配让位；
 * 装配逻辑统一委托 {@link PowerJobWorkerSupport}（2026-08-12 下沉，与 job 共用）。
 */
@Configuration
public class PowerJobConfig {

    @Bean
    @ConditionalOnProperty(prefix = "powerjob.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PowerJobSpringWorker powerJobSpringWorker(PowerJobProperties properties,
                                                     PlatformJobProcessorFactory platformJobProcessorFactory,
                                                     com.datanest.common.scheduler.PowerJobAppBootstrap appBootstrap) {
        return PowerJobWorkerSupport.buildWorker(properties, List.of(platformJobProcessorFactory), appBootstrap);
    }
}
