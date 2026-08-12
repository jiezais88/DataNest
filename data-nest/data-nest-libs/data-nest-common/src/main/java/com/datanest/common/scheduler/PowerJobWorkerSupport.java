package com.datanest.common.scheduler;

import tech.powerjob.common.utils.CommonUtils;
import tech.powerjob.common.utils.NetUtils;
import tech.powerjob.worker.PowerJobSpringWorker;
import tech.powerjob.worker.autoconfigure.PowerJobProperties;
import tech.powerjob.worker.common.PowerJobWorkerConfig;
import tech.powerjob.worker.extension.processor.ProcessorFactory;

import java.util.Arrays;
import java.util.List;

/**
 * PowerJob Worker 装配支撑（2026-08-12 下沉）。
 * <p>
 * 收敛来源：worker 的 PowerJobConfig 与 job 的 PowerJobWorkerConfiguration 装配逻辑逐字相同，
 * 差异仅自定义 {@link ProcessorFactory} 不同。此处统一为静态方法，消费方配置类一行委托即可。
 * <p>
 * 说明：官方 starter 的 {@code PowerJobAutoConfiguration#initPowerJob} 带
 * {@code @ConditionalOnMissingBean}，消费方显式声明 PowerJobSpringWorker Bean 后自动配置让位；
 * 属性映射与官方保持一致，仅追加自定义 processorFactoryList（loader 链中先于内建 factory 被咨询）。
 */
public final class PowerJobWorkerSupport {

    private PowerJobWorkerSupport() {
    }

    /**
     * 组装 {@link PowerJobSpringWorker}（属性映射对齐官方 PowerJobAutoConfiguration）。
     *
     * @param properties  powerjob.worker 配置
     * @param factories    自定义处理器工厂（按需传入，可为空列表）
     * @param appBootstrap App 自举（确保 App 已在 server 注册）
     */
    public static PowerJobSpringWorker buildWorker(PowerJobProperties properties,
                                                   List<ProcessorFactory> factories,
                                                   PowerJobAppBootstrap appBootstrap) {
        PowerJobProperties.Worker worker = properties.getWorker();

        // server 地址，多个用英文逗号分隔，不要带 http:// 前缀
        CommonUtils.requireNonNull(worker.getServerAddress(), "serverAddress can't be empty! " +
                "if you don't want to enable powerjob, please config program arguments: powerjob.worker.enabled=false");
        // 新环境自举：确保 App 已在 server 注册（不存在则经管理员 API 创建，失败仅告警）
        appBootstrap.ensureApp(worker.getAppName());
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
        config.setProcessorFactoryList(factories);
        return new PowerJobSpringWorker(config);
    }
}
