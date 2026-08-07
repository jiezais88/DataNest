package com.datanest.worker.job;

import com.datanest.task.core.service.CollectExecutor;
import org.springframework.stereotype.Component;

/**
 * 元数据采集任务执行 handler（原 XXL-JOB 的 collectTaskHandler）。
 * <p>
 * param 格式原样保留：逗号分隔 {@code taskId[,triggerType]}，
 * triggerType 缺省为 CRON，解析逻辑在 task-core 的 {@link CollectExecutor} 内。
 */
@Component
public class CollectTaskExecuteHandler implements PlatformJobHandler {

    private final CollectExecutor collectExecutor;

    public CollectTaskExecuteHandler(CollectExecutor collectExecutor) {
        this.collectExecutor = collectExecutor;
    }

    @Override
    public String getName() {
        return "collectTaskHandler";
    }

    @Override
    public void execute(String param) {
        collectExecutor.execute(param);
    }
}
