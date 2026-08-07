package com.datanest.worker.job;

/**
 * 平台任务处理器统一接口（PowerJob 迁移后替换 @XxlJob handler）。
 * <p>
 * 每个实现是一个 Spring Bean，{@link #getName()} 即原 XXL-JOB 的 handler 名，
 * PowerJob 任务的 processorInfo 直接填该名称，由
 * {@link PlatformJobProcessorFactory} 按名路由到对应 Bean。
 */
public interface PlatformJobHandler {

    /**
     * handler 名，与 PowerJob 任务的 processorInfo 一一对应
     *
     * @return handler 名（如 syncJobHandler）
     */
    String getName();

    /**
     * 执行任务。param 语义保持历史格式：
     * 调度触发取 jobParams，手动触发取 instanceParams（非空优先）。
     *
     * @param param 执行参数（sync/collect 逗号分隔、quality 冒号分隔或 rule: 前缀）
     */
    void execute(String param);
}
