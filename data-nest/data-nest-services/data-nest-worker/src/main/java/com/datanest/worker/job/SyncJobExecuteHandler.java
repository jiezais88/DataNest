package com.datanest.worker.job;

import com.datanest.task.core.job.SyncJobExecutor;
import org.springframework.stereotype.Component;

/**
 * 同步任务执行 handler（原 XXL-JOB 的 syncJobHandler）。
 * <p>
 * param 格式原样保留：逗号分隔 {@code syncJobId[,triggerType[,historyId]]}，
 * triggerType 缺省为 CRON，解析逻辑在 task-core 的 {@link SyncJobExecutor} 内。
 */
@Component
public class SyncJobExecuteHandler implements PlatformJobHandler {

    private final SyncJobExecutor syncJobExecutor;

    public SyncJobExecuteHandler(SyncJobExecutor syncJobExecutor) {
        this.syncJobExecutor = syncJobExecutor;
    }

    @Override
    public String getName() {
        return "syncJobHandler";
    }

    @Override
    public void execute(String param) {
        syncJobExecutor.execute(param);
    }
}
