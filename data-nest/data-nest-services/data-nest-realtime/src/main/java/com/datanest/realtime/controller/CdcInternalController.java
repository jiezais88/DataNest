package com.datanest.realtime.controller;

import com.datanest.common.model.Result;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.realtime.api.dto.CdcPipelineSubscribeDTO;
import com.datanest.realtime.service.CdcMonitorService;
import com.datanest.realtime.service.CdcPipelineService;
import com.datanest.realtime.service.MetricRetentionCleaner;
import com.datanest.realtime.service.MetricSnapshotWriter;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实时 CDC 服务内部接口（实现 realtime-api 的 Feign 契约）。
 * <p>
 * 仅供服务间内部调用，路径挂在 context-path /realtime 下（servlet path 以 /internal/ 开头），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 * <p>
 * 2026-08-17：原 realtime 侧 @Scheduled 本地调度（监控轮询/分钟落库/保留期清理）统一迁至
 * app-job 按 PowerJob cron 调度，经本控制器端点远程触发；执行逻辑与内存状态仍在 realtime 服务内。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal")
public class CdcInternalController {

    private final CdcPipelineService pipelineService;
    private final CdcMonitorService monitorService;
    private final MetricSnapshotWriter metricSnapshotWriter;
    private final MetricRetentionCleaner metricRetentionCleaner;

    public CdcInternalController(CdcPipelineService pipelineService,
                                 CdcMonitorService monitorService,
                                 MetricSnapshotWriter metricSnapshotWriter,
                                 MetricRetentionCleaner metricRetentionCleaner) {
        this.pipelineService = pipelineService;
        this.monitorService = monitorService;
        this.metricSnapshotWriter = metricSnapshotWriter;
        this.metricRetentionCleaner = metricRetentionCleaner;
    }

    /** 按源数据源查询引用它的 CDC 管道（engineering 删除数据源前置校验用） */
    @GetMapping("/cdc/pipelines/by-datasource")
    public Result<List<CdcPipelineReferenceDTO>> listByDatasource(@RequestParam Long datasourceId) {
        return Result.ok(pipelineService.listByDatasource(datasourceId));
    }

    /** 批量查询管道 id → name（Sprint 9 F3：app-alert 告警对象名反查/可选对象下拉；ids 为空返回全部） */
    @GetMapping("/cdc/pipelines/names")
    public Result<Map<Long, String>> names(@RequestParam(value = "ids", required = false) List<Long> ids) {
        return Result.ok(pipelineService.names(ids));
    }

    /** 条件刷新 Doris 湖仓 catalog（app-job 定时触发）：仅存在 RUNNING 管道时刷新，返回是否实际刷新 */
    @PostMapping("/cdc/pipelines/refresh-catalog-if-running")
    public Result<Boolean> refreshCatalogIfRunning() {
        return Result.ok(pipelineService.refreshCatalogIfRunning());
    }

    /** 查询管道订阅信息（F4 WebSocket 订阅校验：状态 + 源数据源/库 + 源表清单） */
    @GetMapping("/cdc/pipelines/{id}/subscribe")
    public Result<CdcPipelineSubscribeDTO> getSubscribeInfo(@PathVariable("id") Long id) {
        return Result.ok(pipelineService.subscribeInfo(id));
    }

    /** 触发 RUNNING 管道状态轮询（app-job cdcMonitorPollHandler 调度触发，2026-08-17 迁移） */
    @PostMapping("/cdc-monitor/poll")
    public Result<Void> pollRunningPipelines() {
        monitorService.pollRunningPipelines();
        return Result.ok(null);
    }

    /** 触发事件作业轮询（app-job cdcMonitorPollHandler 调度触发，F4 事件作业 FAILED/外部停止检测） */
    @PostMapping("/cdc-monitor/poll-event-jobs")
    public Result<Void> pollEventJobs() {
        monitorService.pollEventJobs();
        return Result.ok(null);
    }

    /** 触发分钟级指标落库（app-job cdcMetricFlushHandler 调度触发，每 60s） */
    @PostMapping("/cdc-metrics/flush-minute")
    public Result<Void> flushMinuteMetrics() {
        metricSnapshotWriter.flushMinuteSnapshot();
        return Result.ok(null);
    }

    /** 触发分钟指标历史清理（app-job cdcMetricRetentionCleaner 调度触发，每天 03:40） */
    @PostMapping("/cdc-metrics/cleanup")
    public Result<Void> cleanupMetrics() {
        metricRetentionCleaner.cleanExpired();
        return Result.ok(null);
    }
}
