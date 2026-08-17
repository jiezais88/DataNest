package com.datanest.realtime.api;

import com.datanest.common.model.Result;
import com.datanest.realtime.api.fallback.CdcOpsApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 实时 CDC 服务运维触发内部 Feign 契约（2026-08-17）。
 * <p>
 * 原 realtime 侧 @Scheduled 本地调度（监控轮询/分钟落库/保留期清理）统一迁至 app-job
 * 按 PowerJob cron 调度后，本契约供 job 各 handler 远程触发 realtime 执行逻辑；
 * 执行逻辑与内存状态（累加器/告警去重/404 计数）仍留在 realtime 服务内。
 */
@FeignClient(name = "data-nest-realtime", path = "/realtime/internal", contextId = "cdcOpsApi",
        fallbackFactory = CdcOpsApiFallbackFactory.class)
public interface CdcOpsApi {

    /** 触发 RUNNING 管道状态轮询（cdcMonitorPollHandler） */
    @PostMapping("/cdc-monitor/poll")
    Result<Void> pollRunningPipelines();

    /** 触发事件作业轮询（cdcMonitorPollHandler，F4） */
    @PostMapping("/cdc-monitor/poll-event-jobs")
    Result<Void> pollEventJobs();

    /** 触发分钟级指标落库（cdcMetricFlushHandler，每 60s） */
    @PostMapping("/cdc-metrics/flush-minute")
    Result<Void> flushMinuteMetrics();

    /** 触发分钟指标历史清理（cdcMetricRetentionCleaner，每天 03:40） */
    @PostMapping("/cdc-metrics/cleanup")
    Result<Void> cleanupMetrics();
}
