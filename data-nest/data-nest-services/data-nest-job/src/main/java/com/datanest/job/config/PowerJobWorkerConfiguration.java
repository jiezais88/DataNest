package com.datanest.job.config;

import com.datanest.common.scheduler.PowerJobWorkerSupport;
import com.datanest.job.powerjob.TechPowerJobRouterFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.powerjob.worker.PowerJobSpringWorker;
import tech.powerjob.worker.autoconfigure.PowerJobProperties;

import java.util.List;

/**
 * PowerJob Worker 装配：在官方 starter 自动配置的基础上挂载自定义 ProcessorFactory。
 * <p>
 * 官方 PowerJobAutoConfiguration#initPowerJob 带 @ConditionalOnMissingBean，
 * 这里显式声明 PowerJobSpringWorker Bean 使其自动配置回退；属性映射与官方保持一致，
 * 仅追加 TechPowerJobRouterFactory（processorInfo 按 handler 名路由到 PlatformJobHandler Bean）。
 * 装配逻辑统一委托 {@link PowerJobWorkerSupport}（2026-08-12 下沉，与 worker 共用）。
 */
@Configuration
@ConditionalOnProperty(prefix = "powerjob.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PowerJobWorkerConfiguration {

    @Bean
    public PowerJobSpringWorker powerJobSpringWorker(PowerJobProperties properties,
                                                     TechPowerJobRouterFactory routerFactory,
                                                     com.datanest.common.scheduler.PowerJobAppBootstrap appBootstrap) {
        return PowerJobWorkerSupport.buildWorker(properties, List.of(routerFactory), appBootstrap);
    }
}
