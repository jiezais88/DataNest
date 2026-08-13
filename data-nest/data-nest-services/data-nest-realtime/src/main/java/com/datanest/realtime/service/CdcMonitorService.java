package com.datanest.realtime.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.common.constant.AlertConstants;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.entity.CdcPipelineLog;
import com.datanest.realtime.mapper.CdcPipelineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDC 管道运行状态监控：定时轮询 RUNNING 管道的 Flink 作业状态与指标。
 * <p>
 * FAILED → 管道置 ERROR + last_error（root-exception 截断 2000）+ ERROR 日志 + FAILURE 告警；
 * CANCELED/FINISHED/SUSPENDED（外部停止）→ 管道置 STOPPED + 清 flink_job_id + WARN 日志 + EXTERNAL_STOP 告警；
 * RUNNING → 回写 current_lag_seconds（≥0 时）与 total_changes，并累计吞吐/重启次数到内存累加器（分钟降采样）；
 * 状态查询 404（作业丢失，连续 not-found-threshold 轮）→ 归并「外部停止」语义（置 STOPPED + 清 job id + EXTERNAL_STOP 告警）；
 * 状态查询其他异常（连接拒绝/超时=集群不可达）→ 只记 warn 不改状态，避免集群重启误伤。
 * 单管道异常 catch 住不影响其他管道；告警上报 fail-open（app-alert 不可达只记管道日志，不阻断监控主流程）。
 */
@Service
public class CdcMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(CdcMonitorService.class);

    private final CdcPipelineMapper pipelineMapper;
    private final FlinkJobService flinkJobService;
    private final CdcPipelineService pipelineService;
    private final AlertApi alertApi;

    /** 延迟告警阈值（秒），超过写一条 WARN 管道日志 + LAG_EXCEEDED 告警 */
    @Value("${datanest.realtime.lag.warn-threshold:30}")
    private Integer lagWarnThreshold;

    /** 作业查询连续 404 轮次阈值：达到才归并「外部停止」（防 Flink 集群短暂抖动误伤，T4） */
    @Value("${datanest.realtime.monitor.not-found-threshold:3}")
    private Integer notFoundThreshold;

    /** 已因延迟超阈值告警过的管道（连续超阈值只写一次，恢复后移除） */
    private final Set<Long> lagWarnedPipelineIds = ConcurrentHashMap.newKeySet();

    /** 作业查询 404 的连续轮次计数（flinkJobId → 连续次数；成功/集群不可达清零）。
     *  key 用 flinkJobId 而非 pipelineId：作业重启/重建后新 jobId 无计数，旧计数自动失效，
     *  不会跨运行残留导致新作业首个 404 就误判（Review 2026-08-11 修复）。 */
    private final Map<String, Integer> notFoundCounts = new ConcurrentHashMap<>();

    /** 内存指标累加器（pipelineId → 本分钟累加值），由 MetricSnapshotWriter 每分钟 flush 落库 */
    private final Map<Long, MetricAccumulator> accumulators = new ConcurrentHashMap<>();

    public CdcMonitorService(CdcPipelineMapper pipelineMapper,
                             FlinkJobService flinkJobService,
                             CdcPipelineService pipelineService,
                             AlertApi alertApi) {
        this.pipelineMapper = pipelineMapper;
        this.flinkJobService = flinkJobService;
        this.pipelineService = pipelineService;
        this.alertApi = alertApi;
    }

    @Scheduled(fixedDelayString = "${datanest.realtime.monitor.interval-ms:5000}", initialDelay = 15000)
    public void pollRunningPipelines() {
        List<CdcPipeline> running = pipelineMapper.selectList(new QueryWrapper<CdcPipeline>()
                .eq("status", CdcPipeline.STATUS_RUNNING)
                .isNotNull("flink_job_id"));
        for (CdcPipeline pipeline : running) {
            try {
                pollOne(pipeline);
            } catch (Exception e) {
                // 单管道异常不影响其他管道
                logger.warn("CDC 管道监控轮询单管道异常: pipelineId={}, error={}", pipeline.getId(), e.getMessage());
            }
        }
    }

    /**
     * F4 事件作业监控：轮询 cdc_events_flink_job_id 非空的管道，事件作业 FAILED/外部停止时
     * 清该字段 + WARN 日志。失败降级不影响主管道状态/指标（独立于 pollOne）。
     */
    @Scheduled(fixedDelayString = "${datanest.realtime.monitor.interval-ms:5000}", initialDelay = 15000)
    public void pollEventJobs() {
        List<CdcPipeline> withEventJob = pipelineMapper.selectList(new QueryWrapper<CdcPipeline>()
                .isNotNull("cdc_events_flink_job_id"));
        for (CdcPipeline pipeline : withEventJob) {
            try {
                pollEventJob(pipeline);
            } catch (Exception e) {
                logger.warn("CDC 事件作业监控单管道异常: pipelineId={}, error={}", pipeline.getId(), e.getMessage());
            }
        }
    }

    private void pollEventJob(CdcPipeline pipeline) {
        String state;
        try {
            state = flinkJobService.extractState(flinkJobService.getJobOverview(pipeline.getCdcEventsFlinkJobId()));
        } catch (Exception e) {
            // 事件作业 404（已被外部删除）→ 清字段；其余（集群不可达）保持原状
            if (isNotFound(e)) {
                pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                        .eq("id", pipeline.getId())
                        .set("cdc_events_flink_job_id", null));
                pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                        "事件作业不存在（已被外部删除），实时订阅已中断；重新启动管道可恢复");
            }
            return;
        }
        switch (state) {
            case "FAILED" -> {
                String rootException = flinkJobService.getJobRootException(pipeline.getCdcEventsFlinkJobId());
                pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                        .eq("id", pipeline.getId())
                        .set("cdc_events_flink_job_id", null));
                pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                        "事件作业失败（不影响主管道同步）: " + truncate(rootException == null ? "未知原因" : rootException, 2000));
            }
            case "CANCELED", "FINISHED", "SUSPENDED" -> {
                pipelineMapper.update(null, new UpdateWrapper<CdcPipeline>()
                        .eq("id", pipeline.getId())
                        .set("cdc_events_flink_job_id", null));
                pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                        "事件作业被外部停止（state=" + state + "），实时订阅已中断；重新启动管道可恢复");
            }
            default -> {
                // RUNNING / 中间状态：忽略
            }
        }
    }

    private void pollOne(CdcPipeline pipeline) {
        Map<String, Object> overview;
        String state;
        try {
            // 一次 /jobs/{id} 调用同时提取状态与指标（内嵌 vertices），避免每轮两次 REST
            overview = flinkJobService.getJobOverview(pipeline.getFlinkJobId());
            state = flinkJobService.extractState(overview);
        } catch (Exception e) {
            if (isNotFound(e)) {
                handleJobNotFound(pipeline);
            } else {
                // 集群不可达/超时：清零 404 计数、保持原状态（避免 Flink 集群重启期间误伤全部管道）
                notFoundCounts.remove(pipeline.getFlinkJobId());
                logger.warn("CDC 管道作业状态查询失败（保持原状态）: pipelineId={}, flinkJobId={}, error={}",
                        pipeline.getId(), pipeline.getFlinkJobId(), e.getMessage());
            }
            return;
        }
        // 查询成功：清零 404 计数
        notFoundCounts.remove(pipeline.getFlinkJobId());

        switch (state) {
            case "FAILED" -> onJobFailed(pipeline);
            case "CANCELED", "FINISHED", "SUSPENDED" -> {
                // stop 的 cancel-with-savepoint 轮询窗口内作业表现为 CANCELED，
                // 由 stop 流程自己收尾（写 savepoint/置 STOPPED），跳过避免误报「外部停止」
                if (pipelineService.isStopping(pipeline.getId())) {
                    logger.debug("CDC 管道停止流程进行中，跳过本轮外部停止处理: pipelineId={}", pipeline.getId());
                } else {
                    onJobStoppedExternally(pipeline, state);
                }
            }
            case "RUNNING" -> onJobRunning(pipeline, overview);
            default -> logger.debug("CDC 管道作业中间状态: pipelineId={}, state={}", pipeline.getId(), state);
        }
    }

    /** 作业 FAILED：置 ERROR + last_error（root-exception 截断 2000）+ ERROR 日志 + FAILURE 告警 */
    private void onJobFailed(CdcPipeline pipeline) {
        String rootException = flinkJobService.getJobRootException(pipeline.getFlinkJobId());
        String lastError = truncate(rootException == null ? "Flink 作业失败（未取到异常详情）" : rootException, 2000);
        UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                .eq("id", pipeline.getId())
                .set("status", CdcPipeline.STATUS_ERROR)
                .set("last_error", lastError);
        pipelineMapper.update(null, update);
        pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_ERROR,
                "Flink 作业失败: " + lastError);
        lagWarnedPipelineIds.remove(pipeline.getId());
        removeAccumulator(pipeline.getId());
        fireAlert(pipeline, AlertConstants.ALERT_FAILURE, "Flink 作业失败: " + lastError);
    }

    /** 作业被外部停止（CANCELED/FINISHED/SUSPENDED）：置 STOPPED + 清 flink_job_id + WARN 日志 + EXTERNAL_STOP 告警 */
    private void onJobStoppedExternally(CdcPipeline pipeline, String state) {
        UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                .eq("id", pipeline.getId())
                .set("status", CdcPipeline.STATUS_STOPPED)
                .set("flink_job_id", null);
        pipelineMapper.update(null, update);
        pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                "Flink 作业被外部停止（state=" + state + "），管道已置为 STOPPED；如需恢复请重新启动");
        lagWarnedPipelineIds.remove(pipeline.getId());
        removeAccumulator(pipeline.getId());
        fireAlert(pipeline, AlertConstants.ALERT_EXTERNAL_STOP, "Flink 作业状态=" + state);
    }

    /**
     * 作业查询连续 404：未达阈值只 warn 保持原状态；达到阈值归并「外部停止」语义
     * （置 STOPPED + 清 flink_job_id + WARN 日志 + EXTERNAL_STOP 告警），防集群重启丢作业后管道永远卡 RUNNING。
     */
    private void handleJobNotFound(CdcPipeline pipeline) {
        int count = notFoundCounts.merge(pipeline.getFlinkJobId(), 1, Integer::sum);
        if (count < notFoundThreshold) {
            logger.warn("CDC 管道作业查询 404（第 {}/{} 轮，未达判定阈值保持原状态）: pipelineId={}, flinkJobId={}",
                    count, notFoundThreshold, pipeline.getId(), pipeline.getFlinkJobId());
            return;
        }
        notFoundCounts.remove(pipeline.getFlinkJobId());
        UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                .eq("id", pipeline.getId())
                .set("status", CdcPipeline.STATUS_STOPPED)
                .set("flink_job_id", null);
        pipelineMapper.update(null, update);
        pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                "Flink 作业不存在（连续 " + count + " 轮查询 404），判定为外部停止，管道已置为 STOPPED；如需恢复请重新启动");
        lagWarnedPipelineIds.remove(pipeline.getId());
        removeAccumulator(pipeline.getId());
        fireAlert(pipeline, AlertConstants.ALERT_EXTERNAL_STOP, "Flink 作业不存在（连续 " + count + " 轮查询 404，NOT_FOUND）");
    }

    /**
     * 作业 RUNNING：回写延迟（≥0 时）与累计变更（≥0 时；-1=查询失败跳过，防误清 0），
     * 提取吞吐/重启次数累计到内存累加器（分钟降采样）；延迟超阈值触发 LAG_EXCEEDED 告警（去重，恢复复位）。
     */
    private void onJobRunning(CdcPipeline pipeline, Map<String, Object> overview) {
        long[] metrics = flinkJobService.extractMetrics(pipeline.getFlinkJobId(), overview);
        long lagSeconds = metrics[0];
        long totalChanges = metrics[1];
        double throughput = flinkJobService.extractThroughput(pipeline.getFlinkJobId(), overview);
        int restarts = queryNumRestarts(pipeline);

        // 累计到内存累加器（本分钟降采样样本；synchronized 防与 flush 线程并发）
        MetricAccumulator acc = accumulators.computeIfAbsent(pipeline.getId(), k -> new MetricAccumulator());
        acc.accumulate(lagSeconds, throughput, restarts, totalChanges);

        Integer newLag = lagSeconds >= 0 ? (int) lagSeconds : null;
        Long newTotal = totalChanges >= 0 ? totalChanges : null;
        boolean lagSame = newLag == null || Objects.equals(newLag, pipeline.getCurrentLagSeconds());
        boolean totalSame = newTotal == null || Objects.equals(newTotal, pipeline.getTotalChanges());
        if (!lagSame || !totalSame) {
            UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                    .eq("id", pipeline.getId());
            if (newLag != null) {
                update.set("current_lag_seconds", newLag);
            }
            if (newTotal != null) {
                update.set("total_changes", newTotal);
            }
            pipelineMapper.update(null, update);
        }

        if (lagSeconds >= 0 && lagSeconds > lagWarnThreshold) {
            // 同一管道连续超阈值只告警一次，恢复（≤阈值）后移除标记允许再次告警
            if (lagWarnedPipelineIds.add(pipeline.getId())) {
                pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                        "同步延迟 " + lagSeconds + "s 超过阈值 " + lagWarnThreshold + "s");
                fireAlert(pipeline, AlertConstants.ALERT_LAG_EXCEEDED,
                        "当前延迟 " + lagSeconds + "s，阈值 " + lagWarnThreshold + "s");
            }
        } else {
            lagWarnedPipelineIds.remove(pipeline.getId());
        }
    }

    /** 查询作业累计重启次数（job-level numRestarts）；查询失败按 0 计（不阻断主流程） */
    private int queryNumRestarts(CdcPipeline pipeline) {
        try {
            Map<String, Double> jobMetrics = flinkJobService.getJobMetrics(
                    pipeline.getFlinkJobId(), List.of("numRestarts"));
            Double restarts = jobMetrics.get("numRestarts");
            return restarts == null ? 0 : (int) Math.round(restarts);
        } catch (Exception e) {
            logger.debug("查询 Flink job numRestarts 失败: pipelineId={}, error={}", pipeline.getId(), e.getMessage());
            return 0;
        }
    }

    /**
     * 上报流处理告警（fail-open：app-alert 不可达/熔断只记管道日志，不阻断监控主流程，对齐告警跨域既有约定）。
     */
    private void fireAlert(CdcPipeline pipeline, String alertType, String detail) {
        AlertFireRequest request = new AlertFireRequest();
        request.setObjectType(AlertConstants.OBJECT_TYPE_CDC_PIPELINE);
        request.setObjectId(pipeline.getId());
        request.setAlertType(alertType);
        request.setDetail(detail);
        RemoteCalls.execute("alert.fire", () -> {
            Result<Boolean> result = alertApi.fire(request);
            if (result == null || !Boolean.TRUE.equals(result.data())) {
                logger.info("CDC 告警未命中规则或未发送: pipelineId={}, alertType={}", pipeline.getId(), alertType);
            }
            return true;
        }, true);
    }

    /** 当前内存累加器快照（供 MetricSnapshotWriter flush） */
    public Map<Long, MetricAccumulator> accumulatorSnapshot() {
        return accumulators;
    }

    /** flush 指定管道的累加器后移除（管道非 RUNNING 时清理，避免泄漏） */
    public void removeAccumulator(Long pipelineId) {
        accumulators.remove(pipelineId);
    }

    private boolean isNotFound(Exception e) {
        return e instanceof RestClientResponseException rce && rce.getStatusCode().value() == 404;
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * 单管道分钟级指标累加器（5s 轮询线程 accumulate，60s flush 线程 snapshotAndReset）。
     * <p>
     * 关键语义（Review 2026-08-11 修复）：flush 必须「原子读后重置」——lag/throughput 是**本分钟**
     * 的均值/峰值样本，不清零会累积成「启动至今累计均值/最大」，趋势图全部失真（lagMax 单调不减）。
     * restarts/totalChanges 语义为「该分钟最后一次采样值」（D-D1），保留最新值不清零。
     * 全部方法 synchronized：poll 线程写、flush 线程读，防可见性/竞态。
     */
    public static class MetricAccumulator {

        private long lagSum;
        private int lagSamples;
        private long lagMax;
        private double throughputSum;
        private int throughputSamples;
        private int restarts;
        private long totalChanges;

        /** 累计一轮采样（poll 线程；lag/throughput 无有效样本 < 0 不累计） */
        public synchronized void accumulate(long lagSeconds, double throughput, int restarts, long totalChanges) {
            if (lagSeconds >= 0) {
                lagSum += lagSeconds;
                lagSamples++;
                lagMax = Math.max(lagMax, lagSeconds);
            }
            if (throughput >= 0) {
                throughputSum += throughput;
                throughputSamples++;
            }
            this.restarts = restarts;
            this.totalChanges = totalChanges;
        }

        /**
         * flush 时原子读取本分钟聚合值并清零（lag/吞吐样本），restarts/totalChanges 保留最新值。
         *
         * @return 本分钟快照（无样本的字段为 null）
         */
        public synchronized MinuteSnapshot snapshotAndReset() {
            MinuteSnapshot snapshot = new MinuteSnapshot(
                    lagSamples > 0 ? (int) (lagSum / lagSamples) : null,
                    lagSamples > 0 ? (int) lagMax : null,
                    throughputSamples > 0 ? throughputSum / throughputSamples : null,
                    restarts, totalChanges);
            lagSum = 0;
            lagSamples = 0;
            lagMax = 0;
            throughputSum = 0;
            throughputSamples = 0;
            return snapshot;
        }

        /** 单分钟聚合快照（lag/吞吐本分钟均值峰值；restarts/totalChanges 最新值） */
        public record MinuteSnapshot(Integer lagAvgSeconds, Integer lagMaxSeconds,
                                     Double recordsPerSecondAvg, int restarts, long totalChanges) {
        }
    }
}
