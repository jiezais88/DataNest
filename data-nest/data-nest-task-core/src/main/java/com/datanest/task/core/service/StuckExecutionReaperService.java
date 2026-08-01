package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.datanest.common.constant.ExecutionStatus;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡死 RUNNING 执行收割服务（task-core 域）。
 * <p>
 * worker 崩溃后 sync_job_history 会永久停留在 RUNNING（DagExecutionSyncHandler
 * 对 RUNNING 一律不收尾），node_execution / dag_execution 同理。
 * 本服务把 RUNNING 且开始时间早于阈值的记录标为 FAILED（附收割原因），
 * 使上层状态机（DAG 同步收尾、前端状态展示）能正常收敛。
 * <p>
 * 注意：这三张表没有 updated_at 列，"活跃时间"以 start_time 为准；
 * 长任务（如大批量 Addax 同步）确实可能跑超过阈值，故阈值做成可配置，
 * 默认 2 小时（datanest.task.stuck-running-timeout-minutes）。
 * <p>
 * 调用方：data-nest-job 的 StuckExecutionReaperHandler（XXL-JOB 周期调度）。
 * 不使用整体事务：逐类批量 update 即时提交，避免收割到一半失败导致全部回滚。
 */
@Service
public class StuckExecutionReaperService {

    private static final Logger logger = LoggerFactory.getLogger(StuckExecutionReaperService.class);

    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobMapper syncJobMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final long timeoutMinutes;

    public StuckExecutionReaperService(SyncJobHistoryMapper syncJobHistoryMapper,
                                       SyncJobMapper syncJobMapper,
                                       NodeExecutionMapper nodeExecutionMapper,
                                       DagExecutionMapper dagExecutionMapper,
                                       @Value("${datanest.task.stuck-running-timeout-minutes:120}") long timeoutMinutes) {
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobMapper = syncJobMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.timeoutMinutes = Math.max(1L, timeoutMinutes);
    }

    /**
     * 收割一轮卡死 RUNNING 的执行记录。
     *
     * @return 各类记录收割条数
     */
    public ReapResult reapStuckRunning() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);
        int syncHistories = reapSyncJobHistories(threshold);
        int dagExecutions = reapDagExecutions(threshold);
        int nodeExecutions = reapNodeExecutions(threshold);
        if (syncHistories + dagExecutions + nodeExecutions > 0) {
            logger.info("卡死 RUNNING 收割完成: syncJobHistory={}, dagExecution={}, nodeExecution={}, threshold={}",
                    syncHistories, dagExecutions, nodeExecutions, threshold);
        }
        return new ReapResult(syncHistories, dagExecutions, nodeExecutions);
    }

    /**
     * 收割卡死的 sync_job_history（RUNNING 且 start_time 早于阈值），
     * 并把对应 sync_job 的 execution_status 一并翻为 FAILED（与 SyncJobExecutor.markFailed 行为一致）。
     */
    private int reapSyncJobHistories(LocalDateTime threshold) {
        List<SyncJobHistory> stuck = syncJobHistoryMapper.selectList(
                new QueryWrapper<SyncJobHistory>()
                        .eq("status", ExecutionStatus.RUNNING.getCode())
                        .isNotNull("start_time")
                        .lt("start_time", threshold));
        if (stuck.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = reapReason("同步执行");
        for (SyncJobHistory history : stuck) {
            history.setStatus(ExecutionStatus.FAILED.getCode());
            history.setErrorMessage(reason);
            history.setEndTime(now);
            if (history.getDurationMs() == null && history.getStartTime() != null) {
                history.setDurationMs(Duration.between(history.getStartTime(), now).toMillis());
            }
            syncJobHistoryMapper.updateById(history);

            // 主表 execution_status 仍为 RUNNING 且指向该历史（或未指向任何历史）时才翻转，
            // 避免覆盖同一任务新一轮执行的状态
            syncJobMapper.update(null, new UpdateWrapper<SyncJob>()
                    .set("execution_status", ExecutionStatus.FAILED.getCode())
                    .set("updated_at", now)
                    .eq("id", history.getSyncJobId())
                    .eq("execution_status", ExecutionStatus.RUNNING.getCode())
                    .and(w -> w.isNull("last_history_id").or().eq("last_history_id", history.getId())));
            logger.warn("收割卡死同步执行: syncJobId={}, historyId={}, startTime={}",
                    history.getSyncJobId(), history.getId(), history.getStartTime());
        }
        return stuck.size();
    }

    /**
     * 收割卡死的 dag_execution（RUNNING 且 start_time 早于阈值），
     * 同时把其下未结束的 node_execution 标 FAILED，保持与现有状态机一致
     * （SUCCESS/FAILED/SKIPPED/TERMINATED 均为终态）。
     */
    private int reapDagExecutions(LocalDateTime threshold) {
        List<DagExecution> stuck = dagExecutionMapper.selectList(
                new QueryWrapper<DagExecution>()
                        .eq("status", "RUNNING")
                        .isNotNull("start_time")
                        .lt("start_time", threshold));
        if (stuck.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = reapReason("DAG 执行");
        for (DagExecution execution : stuck) {
            execution.setStatus("FAILED");
            execution.setEndTime(now);
            if (execution.getDurationMs() == null && execution.getStartTime() != null) {
                execution.setDurationMs(Duration.between(execution.getStartTime(), now).toMillis());
            }
            dagExecutionMapper.updateById(execution);

            // 该 DAG 下未结束的节点一并收尾为 FAILED
            nodeExecutionMapper.update(null, new UpdateWrapper<NodeExecution>()
                    .set("status", "FAILED")
                    .set("error_message", reason)
                    .set("end_time", now)
                    .eq("execution_id", execution.getId())
                    .in("status", "WAITING", "RUNNING"));
            logger.warn("收割卡死 DAG 执行: executionId={}, dagId={}, startTime={}",
                    execution.getId(), execution.getDagId(), execution.getStartTime());
        }
        return stuck.size();
    }

    /**
     * 收割卡死的 node_execution（RUNNING 且 start_time 早于阈值）。
     * 覆盖 DAG 已结束但节点残留 RUNNING 的孤儿场景；
     * 属于被收割 DAG 的节点已在 reapDagExecutions 中处理，这里幂等兜底。
     */
    private int reapNodeExecutions(LocalDateTime threshold) {
        LocalDateTime now = LocalDateTime.now();
        return nodeExecutionMapper.update(null, new UpdateWrapper<NodeExecution>()
                .set("status", "FAILED")
                .set("error_message", reapReason("节点执行"))
                .set("end_time", now)
                .eq("status", "RUNNING")
                .isNotNull("start_time")
                .lt("start_time", threshold));
    }

    private String reapReason(String subject) {
        return subject + "卡死收割：RUNNING 超过 " + timeoutMinutes + " 分钟未结束，判定执行方失联，标记为 FAILED";
    }

    /**
     * 收割结果：sync_job_history / dag_execution / node_execution 各自收割条数。
     */
    public record ReapResult(int syncJobHistories, int dagExecutions, int nodeExecutions) {
    }
}
