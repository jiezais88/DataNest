package com.datanest.job.config;

import com.datanest.job.powerjob.TechPowerJobRouterFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.powerjob.common.utils.CommonUtils;
import tech.powerjob.common.utils.NetUtils;
import tech.powerjob.worker.PowerJobSpringWorker;
import tech.powerjob.worker.autoconfigure.PowerJobProperties;
import tech.powerjob.worker.common.PowerJobWorkerConfig;

import java.util.Arrays;
import java.util.List;

/**
 * PowerJob Worker 装配：在官方 starter 自动配置的基础上挂载自定义 ProcessorFactory。
 * <p>
 * 官方 PowerJobAutoConfiguration#initPowerJob 带 @ConditionalOnMissingBean，
 * 这里显式声明 PowerJobSpringWorker Bean 使其自动配置回退；属性映射与官方保持一致，
 * 仅追加 TechPowerJobRouterFactory（processorInfo 按 handler 名路由到 PlatformJobHandler Bean）。
 * 注意：processorFactoryList 必须在 PowerJobSpringWorker 初始化前放入 config，
 * 其 setApplicationContext 会在自定义 factory 之后追加两个内建 Spring factory。
 */
@Configuration
@ConditionalOnProperty(prefix = "powerjob.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PowerJobWorkerConfiguration {

    @Bean
    public PowerJobSpringWorker powerJobSpringWorker(PowerJobProperties properties,
                                                     TechPowerJobRouterFactory routerFactory) {
        PowerJobProperties.Worker worker = properties.getWorker();

        // 以下属性映射与 PowerJobAutoConfiguration#initPowerJob 保持一致
        CommonUtils.requireNonNull(worker.getServerAddress(), "serverAddress can't be empty! " +
                "if you don't want to enable powerjob, please config program arguments: powerjob.worker.enabled=false");
        List<String> serverAddress = Arrays.asList(worker.getServerAddress().split(","));

        PowerJobWorkerConfig config = new PowerJobWorkerConfig();
        if (worker.getPort() != null) {
            config.setPort(worker.getPort());
        } else {
            int port = worker.getAkkaPort();
            if (port <= 0) {
                port = NetUtils.getRandomPort();
            }
            config.setPort(port);
        }
        config.setAppName(worker.getAppName());
        config.setServerAddress(serverAddress);
        config.setProtocol(worker.getProtocol());
        config.setStoreStrategy(worker.getStoreStrategy());
        config.setAllowLazyConnectServer(worker.isAllowLazyConnectServer());
        config.setMaxAppendedWfContextLength(worker.getMaxAppendedWfContextLength());
        config.setTag(worker.getTag());
        config.setMaxHeavyweightTaskNum(worker.getMaxHeavyweightTaskNum());
        config.setMaxLightweightTaskNum(worker.getMaxLightweightTaskNum());
        config.setHealthReportInterval(worker.getHealthReportInterval());

        // 关键扩展点：自定义 ProcessorFactory（loader 链中先于内建 factory 被咨询）
        config.setProcessorFactoryList(List.of(routerFactory));

        return new PowerJobSpringWorker(config);
    }
}
