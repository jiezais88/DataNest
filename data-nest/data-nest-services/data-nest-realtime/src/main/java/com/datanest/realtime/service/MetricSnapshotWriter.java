package com.datanest.realtime.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.mapper.CdcMetricMinuteMapper;
import com.datanest.realtime.mapper.CdcPipelineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CDC 分钟级指标落库（Sprint 9 F1）。
 * <p>
 * 每分钟把 {@link CdcMonitorService} 内存累加器按当前整分钟 upsert 进 cdc_metric_minute
 * （幂等可重入，pipeline_id + minute_at 唯一）；已非 RUNNING 的管道 flush 后移除累加器防泄漏。
 */
@Service
public class MetricSnapshotWriter {

    private static final Logger logger = LoggerFactory.getLogger(MetricSnapshotWriter.class);

    private final CdcMonitorService monitorService;
    private final CdcMetricMinuteMapper metricMapper;
    private final CdcPipelineMapper pipelineMapper;

    public MetricSnapshotWriter(CdcMonitorService monitorService,
                                CdcMetricMinuteMapper metricMapper,
                                CdcPipelineMapper pipelineMapper) {
        this.monitorService = monitorService;
        this.metricMapper = metricMapper;
        this.pipelineMapper = pipelineMapper;
    }

    /**
     * 分钟级指标落库（原 @Scheduled 本地调度，2026-08-17 迁至 app-job 统一调度，
     * 由 CdcInternalController.flushMinute 端点经 Feign 每 60s 触发）。
     */
    public void flushMinuteSnapshot() {
        Map<Long, CdcMonitorService.MetricAccumulator> snapshot = monitorService.accumulatorSnapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        LocalDateTime minuteAt = LocalDateTime.now().withSecond(0).withNano(0);

        // 当前 RUNNING 管道集合：flush 非 RUNNING 的累加器后移除，避免停用管道的内存泄漏
        Set<Long> runningIds = pipelineMapper.selectList(new QueryWrapper<CdcPipeline>()
                        .eq("status", CdcPipeline.STATUS_RUNNING)
                        .select("id"))
                .stream().map(CdcPipeline::getId).collect(java.util.stream.Collectors.toSet());

        for (Map.Entry<Long, CdcMonitorService.MetricAccumulator> entry : snapshot.entrySet()) {
            Long pipelineId = entry.getKey();
            CdcMonitorService.MetricAccumulator acc = entry.getValue();
            try {
                // 原子「读后重置」：本分钟均值/峰值（不清零会累积成启动至今均值，趋势失真——Review 修复）
                CdcMonitorService.MetricAccumulator.MinuteSnapshot s = acc.snapshotAndReset();
                metricMapper.upsertMinute(IdWorker.getId(), pipelineId, minuteAt,
                        s.lagAvgSeconds(), s.lagMaxSeconds(), s.recordsPerSecondAvg(),
                        s.restarts(), s.totalChanges());
            } catch (Exception e) {
                logger.warn("CDC 分钟指标落库失败: pipelineId={}, minuteAt={}, error={}",
                        pipelineId, minuteAt, e.getMessage());
            }
            // 管道已非 RUNNING：flush 后移除累加器
            if (!runningIds.contains(pipelineId)) {
                monitorService.removeAccumulator(pipelineId);
            }
        }
    }
}
