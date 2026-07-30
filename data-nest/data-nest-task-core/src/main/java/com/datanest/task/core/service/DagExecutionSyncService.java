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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final DagMapper dagMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;

    public DagExecutionSyncService(DagMapper dagMapper, DagExecutionMapper dagExecutionMapper,
                                   NodeExecutionMapper nodeExecutionMapper) {
        this.dagMapper = dagMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
    }

    /**
     * 同步所有 RUNNING 的 dag_execution（分页）
     * 决策：调用方提供 DS 任务实例查询器（解耦 DS API 依赖，job 服务不依赖 engineering）
     * 决策：调用方提供 SYNC 节点历史查询器（Sprint 3 P1-2）
     */
    public int syncRunningExecutions(DsTaskInstanceFetcher fetcher, SyncJobHistoryFetcher syncFetcher) {
        if (fetcher == null) {
            throw new IllegalArgumentException("DsTaskInstanceFetcher 不能为空");
        }
        int totalSynced = 0;
        long pageNo = 1;
        while (true) {
            IPage<DagExecution> page = dagExecutionMapper.selectPage(
                    new Page<>(pageNo, PAGE_SIZE),
                    new QueryWrapper<DagExecution>().eq("status", "RUNNING").orderByAsc("id"));
            List<DagExecution> running = page.getRecords();
            if (running.isEmpty()) break;
            for (DagExecution ex : running) {
                try {
                    if (syncOne(ex, fetcher, syncFetcher)) totalSynced++;
                } catch (Exception e) {
                    logger.warn("同步执行状态失败: executionId={}", ex.getId(), e);
                }
            }
            if (running.size() < PAGE_SIZE) break;
            pageNo++;
        }
        return totalSynced;
    }

    /**
     * 兼容老调用：仅同步 DS 任务实例（不处理 SYNC 节点收尾）
     */
    public int syncRunningExecutions(DsTaskInstanceFetcher fetcher) {
        return syncRunningExecutions(fetcher, null);
    }

    /**
     * 同步单个 dag_execution
     * @return true 表示有状态变更
     */
    private boolean syncOne(DagExecution execution, DsTaskInstanceFetcher fetcher, SyncJobHistoryFetcher syncFetcher) {
        Dag dag = dagMapper.selectById(execution.getDagId());
        if (dag == null || dag.getDsProjectCode() == null || execution.getDsProcessInstanceId() == null) {
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
            if (ti.startTime() != null) {
                LocalDateTime t = parseDsTime(ti.startTime());
                if (t != null && !t.equals(ne.getStartTime())) {
                    ne.setStartTime(t);
                    changed = true;
                }
            }
            if (ti.endTime() != null) {
                LocalDateTime t = parseDsTime(ti.endTime());
                if (t != null && !t.equals(ne.getEndTime())) {
                    ne.setEndTime(t);
                    changed = true;
                }
            }
            if (ti.duration() != null && !ti.duration().equals(ne.getDurationMs())) {
                ne.setDurationMs(ti.duration());
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
                } else if ("FAILED".equalsIgnoreCase(sh.status())) {
                    ne.setStatus("FAILED");
                    ne.setEndTime(sh.endTime() != null ? sh.endTime() : LocalDateTime.now());
                    ne.setErrorMessage(sh.errorMessage());
                    updatedNodes.add(ne);
                    changed = true;
                }
            }
        }

        // 性能3：批量 update
        if (!updatedNodes.isEmpty()) {
            for (NodeExecution ne : updatedNodes) {
                nodeExecutionMapper.updateById(ne);
            }
        }

        // 4. 推断 workflow 整体状态（性能7：复用 nodeList 不再查一次）
        if (!nodeList.isEmpty()) {
            boolean allDone = nodeList.stream().allMatch(n -> {
                String s = n.getStatus();
                return "SUCCESS".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s) || "SKIPPED".equalsIgnoreCase(s);
            });
            if (allDone) {
                boolean anyFailed = nodeList.stream().anyMatch(n -> "FAILED".equalsIgnoreCase(n.getStatus()));
                String newWorkflowStatus = anyFailed ? "FAILED" : "SUCCESS";
                if (!newWorkflowStatus.equals(execution.getStatus())) {
                    execution.setStatus(newWorkflowStatus);
                    execution.setEndTime(LocalDateTime.now());
                    if (execution.getStartTime() != null) {
                        execution.setDurationMs(Duration.between(
                                execution.getStartTime(), execution.getEndTime()).toMillis());
                    }
                    dagExecutionMapper.updateById(execution);
                    changed = true;
                    logger.info("DAG 执行完成: executionId={}, status={}", execution.getId(), newWorkflowStatus);
                }
            }
        }
        return changed;
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
     * DS TaskInstance 简化版（独立于 engineering DTO，避免 task-core 依赖 engineering）
     */
    public record DsTaskInstance(Long id, String name, Integer state,
                                 String startTime, String endTime, Long duration,
                                 String errorMessage) {
    }
}
