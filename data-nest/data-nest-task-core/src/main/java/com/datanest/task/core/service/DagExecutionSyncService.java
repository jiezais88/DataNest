package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.DagMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DAG 执行状态同步服务（task-core 域）
 * 职责：拉取所有 RUNNING 的 dag_execution，调 DS API 同步状态
 * 不依赖 DS SDK（用 RestTemplate 在调用方注入）
 *
 * 调用方：data-nest-job 的 DagExecutionSyncHandler（XXL-JOB 调度，每 5 秒）
 * 决策 Sprint 3 Phase 7：定时回查放 job 服务，由 XXL-JOB 集群协调
 *
 * Sprint 3 修复：
 * - P1-2：SYNC 节点在 RUNNING 时反查 sync_job_history 收尾
 * - 性能3：分页查询 + 批量 updateById
 * - 性能7：复用 nodeByName.values()，避免重复查库
 */
@Service
public class DagExecutionSyncService {

    private static final Logger logger = LoggerFactory.getLogger(DagExecutionSyncService.class);

    /** 性能3：分页大小，避免一次性加载所有 RUNNING execution */
    private static final int PAGE_SIZE = 100;

    /** 性能优化：dag 信息本地缓存（多实例下各自独立，最终一致） */
    private final Cache<Long, Dag> dagCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private final DagMapper dagMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final long silentPeriodMs;

    public DagExecutionSyncService(DagMapper dagMapper, DagExecutionMapper dagExecutionMapper,
                                   NodeExecutionMapper nodeExecutionMapper,
                                   @Value("${datanest.job.dag-sync.silent-period-ms:10000}") long silentPeriodMs) {
        this.dagMapper = dagMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.silentPeriodMs = Math.max(0L, silentPeriodMs);
    }

    /**
     * 同步所有 RUNNING 的 dag_execution（分页）
     * 决策：调用方提供 DS 任务实例查询器（解耦 DS API 依赖，job 服务不依赖 engineering）
     * 决策：调用方提供 SYNC 节点历史查询器（Sprint 3 P1-2）
     * 决策 Sprint3-Fix4：调用方可提供 SyncJobMutexReleaser，sync_job_history 显示 SUCCESS/FAILED 时
     *                    释放 syncJobId 互斥锁（让 callback 拿锁后不 finally 释放的锁得以回收）
     */
    public SyncResult syncRunningExecutions(DsTaskInstanceFetcher fetcher, SyncJobHistoryFetcher syncFetcher,
                                            SyncJobMutexReleaser mutexReleaser) {
        if (fetcher == null) {
            throw new IllegalArgumentException("DsTaskInstanceFetcher 不能为空");
        }
        int totalSynced = 0;
        boolean stillRunning = false;
        long now = System.currentTimeMillis();
        long pageNo = 1;
        while (true) {
            IPage<DagExecution> page = dagExecutionMapper.selectPage(
                    new Page<>(pageNo, PAGE_SIZE),
                    new QueryWrapper<DagExecution>().eq("status", "RUNNING").orderByAsc("id"));
            List<DagExecution> running = page.getRecords();
            if (running.isEmpty()) break;

            // 性能优化：批量查询当前页所有 dag，避免每个 execution 都 selectById
            Map<Long, Dag> dagMap = resolveDagBatch(running);

            for (DagExecution ex : running) {
                // 静默期：刚 trigger 的 execution 给 DS 启动时间，避免无效同步
                if (isInSilentPeriod(ex, now)) {
                    logger.debug("DAG 执行处于静默期，跳过同步: executionId={}", ex.getId());
                    stillRunning = true;
                    continue;
                }
                try {
                    if (syncOne(ex, dagMap.get(ex.getDagId()), fetcher, syncFetcher, mutexReleaser)) {
                        totalSynced++;
                    }
                } catch (Exception e) {
                    logger.warn("同步执行状态失败: executionId={}", ex.getId(), e);
                }
                if ("RUNNING".equalsIgnoreCase(ex.getStatus())) {
                    stillRunning = true;
                }
            }
            if (running.size() < PAGE_SIZE) break;
            pageNo++;
        }
        return new SyncResult(totalSynced, stillRunning);
    }

    private boolean isInSilentPeriod(DagExecution execution, long now) {
        if (silentPeriodMs <= 0) return false;
        LocalDateTime startTime = execution.getStartTime();
        if (startTime == null) return false;
        long startMs = startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return now - startMs < silentPeriodMs;
    }

    /**
     * 兼容老调用：仅同步 DS 任务实例（不处理 SYNC 节点收尾、不释放互斥锁）
     */
    public SyncResult syncRunningExecutions(DsTaskInstanceFetcher fetcher) {
        return syncRunningExecutions(fetcher, null, null);
    }

    /**
     * 兼容老调用：仅同步 + SYNC 收尾（不释放互斥锁）
     */
    public SyncResult syncRunningExecutions(DsTaskInstanceFetcher fetcher, SyncJobHistoryFetcher syncFetcher) {
        return syncRunningExecutions(fetcher, syncFetcher, null);
    }

    /**
     * 同步单个 dag_execution
     * @return true 表示有状态变更
     */
    private boolean syncOne(DagExecution execution, Dag dag, DsTaskInstanceFetcher fetcher,
                            SyncJobHistoryFetcher syncFetcher, SyncJobMutexReleaser mutexReleaser) {
        if (dag == null) {
            // DAG 已删除，对应的执行记录不应再被同步，直接标记为 FAILED
            logger.warn("DAG 已删除，标记 execution 为 FAILED: executionId={}, dagId={}",
                    execution.getId(), execution.getDagId());
            execution.setStatus("FAILED");
            execution.setEndTime(LocalDateTime.now());
            if (execution.getStartTime() != null) {
                execution.setDurationMs(Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis());
            }
            dagExecutionMapper.updateById(execution);
            return true;
        }
        if (dag.getDsProjectCode() == null || execution.getDsProcessInstanceId() == null) {
            return false;
        }

        // 1. 拉 DS task instances
        List<DsTaskInstance> tasks = fetcher.listTaskInstances(
                dag.getDsProjectCode(), execution.getDsProcessInstanceId());
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }

        // 2. 用 nodeName 匹配本地 node_execution
        List<NodeExecution> nodeList = nodeExecutionMapper.selectByExecutionId(execution.getId());
        Map<String, NodeExecution> nodeByName = new HashMap<>();
        for (NodeExecution ne : nodeList) {
            nodeByName.put(ne.getNodeName(), ne);
        }

        boolean changed = false;
        // 性能3：累积变更后批量 update
        List<NodeExecution> updatedNodes = new ArrayList<>();

        for (DsTaskInstance ti : tasks) {
            NodeExecution ne = nodeByName.get(ti.name());
            if (ne == null) continue;
            if (ti.id() != null && !ti.id().equals(ne.getDsTaskInstanceId())) {
                ne.setDsTaskInstanceId(ti.id());
                changed = true;
            }
            String newStatus = mapDsState(ti.state());
            if (!newStatus.equals(ne.getStatus())) {
                ne.setStatus(newStatus);
                changed = true;
            }
            // 节点起止时间优先保留 callback / sync_history 写入的毫秒精度记录；
            // 仅在本地没有时才用 DS 返回的秒级时间兜底。
            if (ne.getStartTime() == null && ti.startTime() != null) {
                LocalDateTime t = parseDsTime(ti.startTime());
                if (t != null) {
                    ne.setStartTime(t);
                    changed = true;
                }
            }
            if (ne.getEndTime() == null && ti.endTime() != null) {
                LocalDateTime t = parseDsTime(ti.endTime());
                if (t != null) {
                    ne.setEndTime(t);
                    changed = true;
                }
            }
            // 节点耗时保留毫秒精度：只有本地没有耗时且起止时间都已存在时才兜底计算
            if (ne.getDurationMs() == null && ne.getStartTime() != null && ne.getEndTime() != null) {
                long duration = Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis();
                ne.setDurationMs(duration);
                changed = true;
            }
            if (ti.errorMessage() != null && !ti.errorMessage().equals(ne.getErrorMessage())) {
                ne.setErrorMessage(ti.errorMessage());
                changed = true;
            }
            if (changed) {
                updatedNodes.add(ne);
            }
        }

        // 3. P1-2：SYNC 节点 RUNNING 状态收尾（查 sync_job_history）
        if (syncFetcher != null) {
            for (NodeExecution ne : nodeList) {
                if (!"RUNNING".equalsIgnoreCase(ne.getStatus())) continue;
                if (!"SYNC".equalsIgnoreCase(ne.getNodeType())) continue;
                if (ne.getSyncJobId() == null) continue;
                SyncHistoryResult sh = syncFetcher.fetchLatestHistory(ne.getSyncJobId());
                if (sh == null) continue;
                if ("SUCCESS".equalsIgnoreCase(sh.status())) {
                    ne.setStatus("SUCCESS");
                    ne.setEndTime(sh.endTime() != null ? sh.endTime() : LocalDateTime.now());
                    if (sh.endTime() != null && ne.getStartTime() != null) {
                        ne.setDurationMs(Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis());
                    }
                    ne.setOutputInfo(sh.outputInfo());
                    updatedNodes.add(ne);
                    changed = true;
                    // Sprint3-Fix4：sync 跑完释放互斥锁，让 callback 端能拿到下一个并发请求
                    if (mutexReleaser != null) {
                        try {
                            mutexReleaser.release(ne.getSyncJobId());
                        } catch (Exception e) {
                            logger.warn("释放 sync 互斥锁失败: syncJobId={}", ne.getSyncJobId(), e);
                        }
                    }
                } else if ("FAILED".equalsIgnoreCase(sh.status())) {
                    ne.setStatus("FAILED");
                    ne.setEndTime(sh.endTime() != null ? sh.endTime() : LocalDateTime.now());
                    ne.setErrorMessage(sh.errorMessage());
                    updatedNodes.add(ne);
                    changed = true;
                    // FAILED 也要释放锁（哪怕失败了，新一轮也要能起来）
                    if (mutexReleaser != null) {
                        try {
                            mutexReleaser.release(ne.getSyncJobId());
                        } catch (Exception e) {
                            logger.warn("释放 sync 互斥锁失败: syncJobId={}", ne.getSyncJobId(), e);
                        }
                    }
                }
            }
        }

        // 性能3：批量 update
        if (!updatedNodes.isEmpty()) {
            int updated = nodeExecutionMapper.updateBatch(updatedNodes);
            if (updated < updatedNodes.size()) {
                // 乐观锁跳过：并发写入（如 callback）已 bump version，本轮快照过期，
                // 下一轮 sync 会基于最新数据重试，无需额外处理
                logger.warn("node_execution 批量更新存在版本冲突跳过: 期望更新={}, 实际更新={}, executionId={}",
                        updatedNodes.size(), updated, execution.getId());
            }
        }

        // 4. 推断 workflow 整体状态（性能7：复用 nodeList 不再查一次）
        if (!nodeList.isEmpty()) {
            boolean allDone = nodeList.stream().allMatch(n -> {
                String s = n.getStatus();
                return "SUCCESS".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s)
                        || "SKIPPED".equalsIgnoreCase(s) || "TERMINATED".equalsIgnoreCase(s);
            });
            if (allDone) {
                boolean anyFailed = nodeList.stream().anyMatch(n -> {
                    String s = n.getStatus();
                    return "FAILED".equalsIgnoreCase(s) || "TERMINATED".equalsIgnoreCase(s);
                });
                String newWorkflowStatus = anyFailed ? "FAILED" : "SUCCESS";
                if (!newWorkflowStatus.equals(execution.getStatus())) {
                    execution.setStatus(newWorkflowStatus);
                    // 与 DS 保持一致：DAG 结束时间取所有节点 endTime 的最大值
                    LocalDateTime dagEndTime = nodeList.stream()
                            .map(NodeExecution::getEndTime)
                            .filter(t -> t != null)
                            .max(LocalDateTime::compareTo)
                            .orElse(LocalDateTime.now());
                    execution.setEndTime(dagEndTime);
                    // 保留毫秒精度：DAG 耗时 = 最后一个节点完成时间 - DAG 开始时间
                    if (execution.getStartTime() != null) {
                        execution.setDurationMs(Duration.between(
                                execution.getStartTime(), dagEndTime).toMillis());
                    }
                    dagExecutionMapper.updateById(execution);
                    changed = true;
                    logger.info("DAG 执行完成: executionId={}, status={}, endTime={}, durationMs={}",
                            execution.getId(), newWorkflowStatus, execution.getEndTime(), execution.getDurationMs());
                }
            }
        }
        return changed;
    }

    /**
     * 性能优化：优先从 Caffeine 缓存取 dag，miss 再查库。
     * dag 的 ds_project_code 基本只读，缓存 5 分钟可大幅减少同步时的 DB 查询。
     */
    private Dag resolveDag(Long dagId) {
        if (dagId == null) return null;
        Dag cached = dagCache.getIfPresent(dagId);
        if (cached != null) return cached;
        Dag dag = dagMapper.selectById(dagId);
        if (dag != null) {
            dagCache.put(dagId, dag);
        }
        return dag;
    }

    /**
     * 性能优化：批量查询当前页 execution 对应的 dag。
     * 先读缓存，miss 的 id 一次性批量查库，并把结果（含 null）写回缓存。
     */
    private Map<Long, Dag> resolveDagBatch(List<DagExecution> executions) {
        Map<Long, Dag> result = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (DagExecution ex : executions) {
            Long dagId = ex.getDagId();
            if (dagId == null) continue;
            Dag cached = dagCache.getIfPresent(dagId);
            if (cached != null) {
                result.put(dagId, cached);
            } else if (!dagCache.asMap().containsKey(dagId)) {
                // 缓存中既没有命中，也没有缓存过 null，才需要查库
                missingIds.add(dagId);
            }
        }
        if (!missingIds.isEmpty()) {
            List<Dag> dags = dagMapper.selectBatchIds(missingIds);
            for (Dag dag : dags) {
                if (dag != null && dag.getId() != null) {
                    dagCache.put(dag.getId(), dag);
                    result.put(dag.getId(), dag);
                }
            }
            // 把 miss 的 id 也缓存 null，避免下一周期重复查不存在的 dag
            for (Long id : missingIds) {
                if (!result.containsKey(id)) {
                    dagCache.put(id, null);
                }
            }
        }
        return result;
    }

    private String mapDsState(Integer dsState) {
        if (dsState == null) return "WAITING";
        return switch (dsState) {
            case DS_SUCCESS -> "SUCCESS";
            case DS_FAILURE -> "FAILED";
            case DS_STOP, DS_KILL -> "TERMINATED";
            default -> "RUNNING";   // 0/1/2/3/4/8 都视为运行中
        };
    }

    private LocalDateTime parseDsTime(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s.replace(" ", "T"));
        } catch (Exception e) {
            return null;
        }
    }

    // =================== SPI ===================

    private static final int DS_SUCCESS = 7;
    private static final int DS_FAILURE = 6;
    private static final int DS_STOP = 5;
    private static final int DS_KILL = 9;

    /**
     * DS 任务实例查询器 SPI（接口）
     * 调用方实现：data-nest-job 用 RestTemplate 调 DS API
     * 决策：解耦 DS API 依赖，task-core 不需要依赖 DS 配置
     */
    public interface DsTaskInstanceFetcher {
        List<DsTaskInstance> listTaskInstances(Long dsProjectCode, Long dsProcessInstanceId);

        /**
         * 拉取 DS 流程实例 duration（毫秒）。
         * 默认返回 null，由调用方实现；task-core 优先使用该值作为 DAG 整体耗时。
         */
        default Long fetchWorkflowDurationMs(Long dsProjectCode, Long dsProcessInstanceId) {
            return null;
        }
    }

    /**
     * Sprint 3 P1-2：SYNC 节点历史查询器 SPI
     * 调用方实现：data-nest-job 查 sync_job_history 表
     */
    public interface SyncJobHistoryFetcher {
        SyncHistoryResult fetchLatestHistory(Long syncJobId);
    }

    public record SyncHistoryResult(String status, LocalDateTime endTime, String errorMessage, String outputInfo) {
    }

    /**
     * 同步结果：本轮同步了多少条，以及是否仍有 RUNNING 的执行。
     * 用于 job 端做自适应调度（仍有 RUNNING 时缩短下次同步间隔）。
     */
    public record SyncResult(int synced, boolean stillRunning) {
    }

    /**
     * Sprint3-Fix4：sync 互斥锁释放器 SPI
     * 调用方实现：data-nest-job（提供 data-nest-engineering 的 SyncNodeMutexService 代理）
     * 用途：DagExecutionSyncService 检测到 SYNC 节点 SUCCESS/FAILED 时释放锁，
     *      让 callback 端能接受下一个并发请求。
     */
    public interface SyncJobMutexReleaser {
        void release(Long syncJobId);
    }

    /**
     * DS TaskInstance 简化版（独立于 engineering DTO，避免 task-core 依赖 engineering）
     */
    public record DsTaskInstance(Long id, String name, Integer state,
                                 String startTime, String endTime, Long duration,
                                 String errorMessage) {
    }
}
