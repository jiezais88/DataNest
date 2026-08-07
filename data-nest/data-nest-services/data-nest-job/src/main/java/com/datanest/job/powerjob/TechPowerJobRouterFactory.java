package com.datanest.job.powerjob;

import com.datanest.job.handler.PlatformJobHandler;
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
 * 平台定时任务的 PowerJob 处理器路由工厂。
 * <p>
 * 把 PowerJob 任务的 processorInfo 直接解释为 handler 名，路由到实现
 * {@link PlatformJobHandler} 的 Spring Bean；未命中时返回 null，
 * 交由内建的 BuiltInSpringProcessorFactory 等后续 factory 处理（互不影响）。
 * <p>
 * 挂载方式见 PowerJobWorkerConfiguration：自定义 factory 需放入
 * PowerJobWorkerConfig.processorFactoryList，且在 loader 链中先于内建 factory 被咨询。
 */
@Component
public class TechPowerJobRouterFactory implements ProcessorFactory {

    private static final Logger logger = LoggerFactory.getLogger(TechPowerJobRouterFactory.class);

    /** handler 名 -> 实现 Bean */
    private final Map<String, PlatformJobHandler> handlers;

    public TechPowerJobRouterFactory(List<PlatformJobHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(PlatformJobHandler::getName, Function.identity()));
        logger.info("平台任务 handler 路由表初始化完成: handlers={}", handlers.keySet());
    }

    @Override
    public Set<String> supportTypes() {
        // 平台任务在控制台按「内建处理器」配置
        return Set.of(ProcessorType.BUILT_IN.name());
    }

    @Override
    public ProcessorBean build(ProcessorDefinition processorDefinition) {
        String handlerName = processorDefinition.getProcessorInfo();
        if (!StringUtils.hasText(handlerName)) {
            return null;
        }
        PlatformJobHandler handler = handlers.get(handlerName);
        if (handler == null) {
            // 非平台 handler（如其他 BasicProcessor Bean），交后续 factory
            return null;
        }
        return new ProcessorBean()
                .setProcessor(new RoutingProcessor(handler))
                .setClassLoader(handler.getClass().getClassLoader())
                .setStable(true);
    }

    /**
     * PlatformJobHandler → BasicProcessor 适配器。
     * param 语义：instanceParams 非空优先（手动触发），否则 jobParams（调度触发）。
     */
    private static final class RoutingProcessor implements BasicProcessor {

        private final PlatformJobHandler handler;

        private RoutingProcessor(PlatformJobHandler handler) {
            this.handler = handler;
        }

        @Override
        public ProcessResult process(TaskContext context) {
            String param = StringUtils.hasText(context.getInstanceParams())
                    ? context.getInstanceParams() : context.getJobParams();
            try {
                handler.execute(param);
                return new ProcessResult(true, "success");
            } catch (Exception e) {
                logger.error("平台任务执行失败: handler={}, instanceId={}", handler.getName(), context.getInstanceId(), e);
                return new ProcessResult(false, "执行失败: " + e.getMessage());
            }
        }
    }
}
