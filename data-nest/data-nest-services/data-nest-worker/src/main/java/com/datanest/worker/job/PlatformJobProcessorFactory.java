package com.datanest.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tech.powerjob.common.enums.ProcessorType;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;
import tech.powerjob.worker.extension.processor.ProcessorBean;
import tech.powerjob.worker.extension.processor.ProcessorDefinition;
import tech.powerjob.worker.extension.processor.ProcessorFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台任务处理器路由工厂：把 PowerJob 任务的 processorInfo 直接解释为 handler 名，
 * 路由到实现 {@link PlatformJobHandler} 的 Spring Bean。
 * <p>
 * 挂载方式见 {@code PowerJobConfig}（自定义 PowerJobSpringWorker 时注入本工厂，
 * 优先级高于 PowerJob 内建的 Spring 处理器工厂）；未匹配到 handler 名时返回 null，
 * 交由内建工厂兜底（兼容直接填 Bean 名/类名的场景）。
 * <p>
 * 执行结果语义对齐原 XxlJobHelper：执行正常返回 ProcessResult(true)，
 * 抛异常返回 ProcessResult(false, 异常信息)。
 */
@Component
public class PlatformJobProcessorFactory implements ProcessorFactory {

    private static final Logger logger = LoggerFactory.getLogger(PlatformJobProcessorFactory.class);

    /** handler 名 → 处理器 Bean 索引 */
    private final Map<String, PlatformJobHandler> handlerMap;

    public PlatformJobProcessorFactory(List<PlatformJobHandler> handlers) {
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(PlatformJobHandler::getName, Function.identity()));
        logger.info("平台任务 handler 注册完成: {}", handlerMap.keySet());
    }

    @Override
    public Set<String> supportTypes() {
        return Set.of(ProcessorType.BUILT_IN.name());
    }

    @Override
    public ProcessorBean build(ProcessorDefinition processorDefinition) {
        String handlerName = processorDefinition.getProcessorInfo();
        PlatformJobHandler handler = handlerMap.get(handlerName);
        if (handler == null) {
            // 非平台 handler 名，返回 null 让内建工厂继续尝试加载
            return null;
        }
        BasicProcessor processor = new BasicProcessor() {
            @Override
            public ProcessResult process(TaskContext context) {
                // param 语义：手动触发读 instanceParams（非空优先），调度触发读 jobParams
                String param = StringUtils.hasText(context.getInstanceParams())
                        ? context.getInstanceParams() : context.getJobParams();
                try {
                    handler.execute(context);
                    return new ProcessResult(true, handlerName + " 执行成功");
                } catch (Exception e) {
                    logger.error("{} 执行失败: param={}", handlerName, param, e);
                    return new ProcessResult(false, handlerName + " 执行失败: " + e.getMessage());
                }
            }
        };
        return new ProcessorBean()
                .setProcessor(processor)
                .setClassLoader(handler.getClass().getClassLoader());
    }
}
