package com.datanest.job.handler;

/**
 * 平台定时任务统一接口（PowerJob 迁移）。
 * <p>
 * 每个实现是一个 Spring Bean，Bean 名/getName() 即 PowerJob 任务的 processorInfo，
 * 由 TechPowerJobRouterFactory 按名路由执行。
 */
public interface PlatformJobHandler {

    /**
     * handler 名（即 PowerJob 任务的 processorInfo）。
     */
    String getName();

    /**
     * 执行任务。
     *
     * @param param 实例参数（instanceParams 非空优先，否则为控制台的 jobParams）
     */
    void execute(String param);
}
