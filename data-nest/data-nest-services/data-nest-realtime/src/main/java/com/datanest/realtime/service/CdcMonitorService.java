package com.datanest.realtime.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.realtime.entity.CdcPipeline;
import com.datanest.realtime.entity.CdcPipelineLog;
import com.datanest.realtime.mapper.CdcPipelineMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDC 管道运行状态监控：定时轮询 RUNNING 管道的 Flink 作业状态与指标。
 * <p>
 * FAILED → 管道置 ERROR + last_error（取 root-exception 截断 2000）+ ERROR 日志；
 * CANCELED/FINISHED/SUSPENDED（外部停止）→ 管道置 STOPPED + 清 flink_job_id + WARN 日志；
 * RUNNING → 回写 current_lag_seconds（≥0 时）与 total_changes；
 * 状态查询抛异常（作业不存在/集群不可达）→ 只记 warn 不改状态，避免集群重启误伤。
 * 单管道异常 catch 住不影响其他管道。
 */
@Service
public class CdcMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(CdcMonitorService.class);

    private final CdcPipelineMapper pipelineMapper;
    private final FlinkJobService flinkJobService;
    private final CdcPipelineService pipelineService;

    /** 延迟告警阈值（秒），超过写一条 WARN 管道日志 */
    @Value("${datanest.realtime.lag.warn-threshold:30}")
    private Integer lagWarnThreshold;

    /** 已因延迟超阈值告警过的管道（连续超阈值只写一次，恢复后移除） */
    private final Set<Long> lagWarnedPipelineIds = ConcurrentHashMap.newKeySet();

    public CdcMonitorService(CdcPipelineMapper pipelineMapper,
                             FlinkJobService flinkJobService,
                             CdcPipelineService pipelineService) {
        this.pipelineMapper = pipelineMapper;
        this.flinkJobService = flinkJobService;
        this.pipelineService = pipelineService;
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

    private void pollOne(CdcPipeline pipeline) {
        Map<String, Object> overview;
        String state;
        try {
            // 一次 /jobs/{id} 调用同时提取状态与指标（内嵌 vertices），避免每轮两次 REST
            overview = flinkJobService.getJobOverview(pipeline.getFlinkJobId());
            state = flinkJobService.extractState(overview);
        } catch (Exception e) {
            // 作业不存在/集群不可达：只记 warn 不改状态（避免 Flink 集群重启期间误伤全部管道）
            logger.warn("CDC 管道作业状态查询失败（保持原状态）: pipelineId={}, flinkJobId={}, error={}",
                    pipeline.getId(), pipeline.getFlinkJobId(), e.getMessage());
            return;
        }

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

    /** 作业 FAILED：置 ERROR + last_error（root-exception 截断 2000）+ ERROR 日志 */
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
    }

    /** 作业被外部停止（CANCELED/FINISHED/SUSPENDED）：置 STOPPED + 清 flink_job_id + WARN 日志 */
    private void onJobStoppedExternally(CdcPipeline pipeline, String state) {
        UpdateWrapper<CdcPipeline> update = new UpdateWrapper<CdcPipeline>()
                .eq("id", pipeline.getId())
                .set("status", CdcPipeline.STATUS_STOPPED)
                .set("flink_job_id", null);
        pipelineMapper.update(null, update);
        pipelineService.writeLog(pipeline.getId(), CdcPipelineLog.LEVEL_WARN,
                "Flink 作业被外部停止（state=" + state + "），管道已置为 STOPPED；如需恢复请重新启动");
        lagWarnedPipelineIds.remove(pipeline.getId());
    }

    /** 作业 RUNNING：回写延迟（≥0 时）与累计变更（≥0 时；-1=查询失败跳过，防误清 0）；无变化跳过写库 */
    private void onJobRunning(CdcPipeline pipeline, Map<String, Object> overview) {
        long[] metrics = flinkJobService.extractMetrics(pipeline.getFlinkJobId(), overview);
        long lagSeconds = metrics[0];
        long totalChanges = metrics[1];

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
            }
        } else {
            lagWarnedPipelineIds.remove(pipeline.getId());
        }
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
