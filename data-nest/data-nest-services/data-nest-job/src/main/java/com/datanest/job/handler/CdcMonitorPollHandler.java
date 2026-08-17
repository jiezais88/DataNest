package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcOpsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CDC 管道运行状态轮询（2026-08-17：原 realtime 侧 @Scheduled 本地调度迁至 job 统一调度）。
 * <p>
 * cron 由 JobRegistrar 按 datanest.job.cdc-monitor-poll.interval-ms（默认 5000）生成，
 * 配置变更经 Nacos 热更新后 JobRegistrar 重注册任务。经 Feign 触发 realtime 内部端点执行，
 * 内存状态（累加器/告警去重/404 计数）留在 realtime。fail-open：realtime 不可达本轮跳过下轮再来。
 */
@Component
public class CdcMonitorPollHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(CdcMonitorPollHandler.class);

    private final CdcOpsApi cdcOpsApi;

    public CdcMonitorPollHandler(CdcOpsApi cdcOpsApi) {
        this.cdcOpsApi = cdcOpsApi;
    }

    @Override
    public String getName() {
        return "cdcMonitorPollHandler";
    }

    @Override
    public void execute(String param) {
        RemoteCalls.execute("realtime.cdc-monitor.poll", () -> {
            Result<Void> result = cdcOpsApi.pollRunningPipelines();
            return result == null ? null : true;
        }, null);
        RemoteCalls.execute("realtime.cdc-monitor.poll-event-jobs", () -> {
            Result<Void> result = cdcOpsApi.pollEventJobs();
            return result == null ? null : true;
        }, null);
        logger.debug("CDC monitor poll triggered");
    }
}
