package com.datanest.worker.config;

import com.datanest.worker.job.PlatformJobProcessorFactory;
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
 * PowerJob Worker 配置：在 starter 自动装配的基础上追加自定义
 * {@link PlatformJobProcessorFactory}（按 processorInfo 路由平台 handler）。
 * <p>
 * starter 的 {@code PowerJobAutoConfiguration#initPowerJob} 带
 * {@code @ConditionalOnMissingBean}，本类显式声明 PowerJobSpringWorker Bean 后自动装配让位；
 * 属性装配逻辑与 starter 保持一致，仅多出 processorFactoryList 的注入。
 */
@Configuration
public class PowerJobConfig {

    @Bean
    @ConditionalOnProperty(prefix = "powerjob.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public PowerJobSpringWorker powerJobSpringWorker(PowerJobProperties properties,
                                                     PlatformJobProcessorFactory platformJobProcessorFactory) {
        PowerJobProperties.Worker worker = properties.getWorker();

        // server 地址，多个用英文逗号分隔，不要带 http:// 前缀
        CommonUtils.requireNonNull(worker.getServerAddress(), "serverAddress can't be empty! " +
                "if you don't want to enable powerjob, please config program arguments: powerjob.worker.enabled=false");
        List<String> serverAddress = Arrays.asList(worker.getServerAddress().split(","));

        PowerJobWorkerConfig config = new PowerJobWorkerConfig();
        // worker 通讯端口，非正数时随机分配
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
        // 追加自定义处理器工厂（PowerJobSpringWorker 初始化时会把内建 Spring 工厂排在其后）
        config.setProcessorFactoryList(List.of(platformJobProcessorFactory));
        return new PowerJobSpringWorker(config);
    }
}
