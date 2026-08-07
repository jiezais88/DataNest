package com.datanest.task.core.service;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.dto.DagExecutionFinalizeRequest;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.DagInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.engineering.api.dto.NodeExecutionBatchUpdateRequest;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
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
 * 微服务化 3.3：dag/dag_execution/node_execution 读写全部经 Feign 调 app-engineering
 * （EngineeringDagApi / EngineeringDagExecutionApi），远程失败经 RemoteCalls 降级，
 * 本轮按无数据处理，下一轮重试；终态收尾调 finalize 端点，DAG 完成告警副作用由
 * engineering 端内置触发（不再走本地监听器）。
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
    private final Cache<Long, DagInfo> dagCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private final EngineeringDagApi dagApi;
    private final EngineeringDagExecutionApi dagExecutionApi;
    private final long silentPeriodMs;

    public DagExecutionSyncService(EngineeringDagApi dagApi,
                                   EngineeringDagExecutionApi dagExecutionApi,
                                   @Value("${datanest.job.dag-sync.silent-period-ms:10000}") long silentPeriodMs) {
        this.dagApi = dagApi;
        this.dagExecutionApi = dagExecutionApi;
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
        int pageNo = 1;
        while (true) {
            final int page = pageNo;
            PageResult<DagExecutionInfo> pageResult = RemoteCalls.execute("engineering.dag-execution.running", () -> {
                Result<PageResult<DagExecutionInfo>> result = dagExecutionApi.listRunning(page, PAGE_SIZE);
                return result == null ? null : result.data();
            }, null);
            List<DagExecutionInfo> running = pageResult == null || pageResult.records() == null
                    ? List.of() : pageResult.records();
            if (running.isEmpty()) break;

            // 性能优化：批量查询当前页所有 dag，避免每个 execution 都 getById
            Map<Long, DagInfo> dagMap = resolveDagBatch(running);

            for (DagExecutionInfo ex : running) {
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

    private boolean isInSilentPeriod(DagExecutionInfo execution, long now) {
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
    private boolean syncOne(DagExecutionInfo execution, DagInfo dag, DsTaskInstanceFetcher fetcher,
                            SyncJobHistoryFetcher syncFetcher, SyncJobMutexReleaser mutexReleaser) {
        if (dag == null) {
            // DAG 已删除，对应的执行记录不应再被同步，直接标记为 FAILED
            logger.warn("DAG 已删除，标记 execution 为 FAILED: executionId={}, dagId={}",
                    execution.getId(), execution.getDagId());
            LocalDateTime endTime = LocalDateTime.now();
            DagExecutionFinalizeRequest request = new DagExecutionFinalizeRequest();
            request.setStatus("FAILED");
            request.setEndTime(endTime);
            if (execution.getStartTime() != null) {
                request.setDurationMs(Duration.between(execution.getStartTime(), endTime).toMillis());
            }
            RemoteCalls.execute("engineering.dag-execution.finalize",
                    () -> dagExecutionApi.finalizeExecution(execution.getId(), request));
            execution.setStatus("FAILED");
            execution.setEndTime(endTime);
            execution.setDurationMs(request.getDurationMs());
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

        // 2. 用 nodeName 匹配远程 node_execution
        List<NodeExecutionInfo> nodeList = listExecutionNodes(execution.getId());
        Map<String, NodeExecutionInfo> nodeByName = new HashMap<>();
        // DS 任务名 = 节点名_节点ID后8位（DagDsConverter.buildDsTaskName），nodeId 本身可能含 `_`，
        // 因此按相同规则生成「DS 任务名 → node」反向映射，供 SUB_DAG 等依赖 sync 同步状态的节点匹配。
        Map<String, NodeExecutionInfo> nodeByDsTaskName = new HashMap<>();
        for (NodeExecutionInfo ne : nodeList) {
            nodeByName.put(ne.getNodeName(), ne);
            if (ne.getNodeId() != null && ne.getNodeName() != null) {
                String nodeId = ne.getNodeId();
                String suffix = nodeId.length() > 8 ? nodeId.substring(nodeId.length() - 8) : nodeId;
                nodeByDsTaskName.put(ne.getNodeName() + "_" + suffix, ne);
            }
        }

        boolean changed = false;
        // 性能3：累积变更后批量 update
        List<NodeExecutionInfo> updatedNodes = new ArrayList<>();

        for (DsTaskInstance ti : tasks) {
            NodeExecutionInfo ne = nodeByName.get(ti.name());
            if (ne == null) {
                ne = nodeByDsTaskName.get(ti.name());
            }
            if (ne == null) continue;
            if (ti.id() != null && !ti.id().equals(ne.getDsTaskInstanceId())) {
                ne.setDsTaskInstanceId(ti.id());
                changed = true;
            }
            String newStatus = mapDsState(ti.state());
            // 终态保护：已被 callback/其它路径置为终态（SKIPPED/SUCCESS/FAILED/TERMINATED）的节点
            // 不允许被 DS 状态回写覆盖（条件分支 gate 的 SKIPPED 会被 DS 侧的 SUCCESS 误复活）。
            // 远程化后 sync 与 callback 存在跨服务时序窗口，终态不可逆是安全不变量。
            if (!isTerminalStatus(ne.getStatus()) && !newStatus.equals(ne.getStatus())) {
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
            for (NodeExecutionInfo ne : nodeList) {
                if (!"RUNNING".equalsIgnoreCase(ne.getStatus())) continue;
                if (!"SYNC".equalsIgnoreCase(ne.getNodeType())) continue;
                if (ne.getSyncJobId() == null) continue;
                // 负数耗时修复：只接受 end_time 不早于本节点 start_time 的 history，
                // 取不到合格 history 时本轮跳过收尾，等下一轮
                SyncHistoryResult sh = syncFetcher.fetchLatestHistory(ne.getSyncJobId(), ne.getStartTime());
                if (sh == null) continue;
                if ("SUCCESS".equalsIgnoreCase(sh.status())) {
                    ne.setStatus("SUCCESS");
                    ne.setEndTime(sh.endTime() != null ? sh.endTime() : LocalDateTime.now());
                    if (sh.endTime() != null && ne.getStartTime() != null) {
                        ne.setDurationMs(Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis());
                    }
                    ne.setOutputInfo(sh.outputInfo());
                    ne.setSyncJobHistoryId(sh.historyId());
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
                    ne.setSyncJobHistoryId(sh.historyId());
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
                } else if ("TERMINATED".equalsIgnoreCase(sh.status())) {
                    // 手动停止：与 FAILED 一样收尾并放锁，仅状态值不同
                    ne.setStatus("TERMINATED");
                    ne.setEndTime(sh.endTime() != null ? sh.endTime() : LocalDateTime.now());
                    ne.setErrorMessage(sh.errorMessage() != null ? sh.errorMessage() : "手动停止");
                    ne.setSyncJobHistoryId(sh.historyId());
                    updatedNodes.add(ne);
                    changed = true;
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

        // 3.5 实例节点数错乱修复：DS 流程实例到达终态后，本地仍 WAITING 的节点（因上游失败
        // 等原因从未在 DS 侧运行）标记为 SKIPPED。只在 DS 终态分支做，运行中实例绝不误标。
        boolean hasWaiting = false;
        for (NodeExecutionInfo ne : nodeList) {
            if ("WAITING".equalsIgnoreCase(ne.getStatus())) {
                hasWaiting = true;
                break;
            }
        }
        if (hasWaiting) {
            Integer workflowState = fetcher.fetchWorkflowState(dag.getDsProjectCode(), execution.getDsProcessInstanceId());
            if (isDsTerminalState(workflowState)) {
                for (NodeExecutionInfo ne : nodeList) {
                    if ("WAITING".equalsIgnoreCase(ne.getStatus())) {
                        ne.setStatus("SKIPPED");
                        ne.setEndTime(LocalDateTime.now());
                        updatedNodes.add(ne);
                        changed = true;
                    }
                }
            }
        }

        // 性能3：批量乐观锁更新（version 冲突语义保留：服务端按 (id, version) 成对匹配，
        // version 不匹配的行跳过不写并返回失败 id 列表）
        if (!updatedNodes.isEmpty()) {
            NodeExecutionBatchUpdateRequest request = new NodeExecutionBatchUpdateRequest();
            request.setUpdates(updatedNodes.stream().map(DagExecutionSyncService::toUpdateItem).toList());
            List<Long> failedIds = RemoteCalls.execute("engineering.node-execution.batch-update", () -> {
                Result<List<Long>> result = dagExecutionApi.batchUpdateNodes(request);
                return result == null || result.data() == null ? List.of() : result.data();
            }, updatedNodes.stream().map(NodeExecutionInfo::getId).toList());
            if (!failedIds.isEmpty()) {
                // 乐观锁跳过：并发写入（如 callback）已 bump version，本轮快照过期，
                // 下一轮 sync 会基于最新数据重试，无需额外处理
                logger.warn("node_execution 批量更新存在版本冲突跳过: 期望更新={}, 跳过={}, executionId={}",
                        updatedNodes.size(), failedIds.size(), execution.getId());
            }
        }

        // 4. 推断 workflow 整体状态（性能7：复用 nodeList 不再查一次）
        // SYNC 节点收尾（步骤 3）也在这里同轮完成 dag_execution 终态推断
        if (finalizeIfAllDone(execution, nodeList)) {
            changed = true;
        }
        return changed;
    }

    /**
     * 所有节点到达终态时收尾 dag_execution（写 status/endTime/durationMs）。
     * 抽成 public 供节点回调（DagNodeCallbackController）在写完节点终态后立即调用，
     * 不必等下一轮定时同步，让 DAG 整体耗时准实时。
     * 仅在 execution 仍为 RUNNING 时处理，已是终态的直接返回。
     *
     * @return true 表示本次把 execution 写成了终态
     */
    public boolean finalizeIfAllDone(Long executionId) {
        if (executionId == null) return false;
        DagExecutionInfo execution = RemoteCalls.execute("engineering.dag-execution.get", () -> {
            Result<DagExecutionInfo> result = dagExecutionApi.getById(executionId);
            return result == null ? null : result.data();
        }, null);
        if (execution == null || !"RUNNING".equalsIgnoreCase(execution.getStatus())) {
            return false;
        }
        List<NodeExecutionInfo> nodeList = listExecutionNodes(executionId);
        return finalizeIfAllDone(execution, nodeList);
    }

    /**
     * allDone 推断核心逻辑：调用方负责保证 nodeList 与 execution 对应。
     * 终态回写调 finalize 端点，DAG 完成告警等副作用由 engineering 端内置触发。
     */
    private boolean finalizeIfAllDone(DagExecutionInfo execution, List<NodeExecutionInfo> nodeList) {
        if (nodeList.isEmpty()) {
            return false;
        }
        boolean allDone = nodeList.stream().allMatch(n -> isTerminalStatus(n.getStatus()));
        if (!allDone) {
            return false;
        }
        boolean anyFailed = nodeList.stream().anyMatch(n -> {
            String s = n.getStatus();
            return "FAILED".equalsIgnoreCase(s) || "TERMINATED".equalsIgnoreCase(s);
        });
        String newWorkflowStatus = anyFailed ? "FAILED" : "SUCCESS";
        if (newWorkflowStatus.equals(execution.getStatus())) {
            return false;
        }
        // 与 DS 保持一致：DAG 结束时间取所有节点 endTime 的最大值
        LocalDateTime dagEndTime = nodeList.stream()
                .map(NodeExecutionInfo::getEndTime)
                .filter(t -> t != null)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());
        // 保留毫秒精度：DAG 耗时 = 最后一个节点完成时间 - DAG 开始时间
        Long durationMs = execution.getStartTime() != null
                ? Duration.between(execution.getStartTime(), dagEndTime).toMillis() : null;
        DagExecutionFinalizeRequest request = new DagExecutionFinalizeRequest();
        request.setStatus(newWorkflowStatus);
        request.setEndTime(dagEndTime);
        request.setDurationMs(durationMs);
        RemoteCalls.execute("engineering.dag-execution.finalize",
                () -> dagExecutionApi.finalizeExecution(execution.getId(), request));
        execution.setStatus(newWorkflowStatus);
        execution.setEndTime(dagEndTime);
        execution.setDurationMs(durationMs);
        logger.info("DAG 执行完成: executionId={}, status={}, endTime={}, durationMs={}",
                execution.getId(), newWorkflowStatus, execution.getEndTime(), execution.getDurationMs());
        return true;
    }

    /** 节点终态判定：SUCCESS/FAILED/SKIPPED/TERMINATED 均为不可逆终态 */
    private static boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                || "SKIPPED".equalsIgnoreCase(status) || "TERMINATED".equalsIgnoreCase(status);
    }

    /** 读取执行实例下全部节点（远程失败降级空列表，本轮按无节点处理，下一轮重试） */
    private List<NodeExecutionInfo> listExecutionNodes(Long executionId) {
        return RemoteCalls.execute("engineering.dag-execution.nodes", () -> {
            Result<List<NodeExecutionInfo>> result = dagExecutionApi.listNodes(executionId);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
    }

    private static NodeExecutionBatchUpdateRequest.UpdateItem toUpdateItem(NodeExecutionInfo ne) {
        NodeExecutionBatchUpdateRequest.UpdateItem item = new NodeExecutionBatchUpdateRequest.UpdateItem();
        item.setId(ne.getId());
        // 乐观锁：更新时期望的当前 version 为读取时的快照值
        item.setVersion(ne.getVersion());
        item.setStatus(ne.getStatus());
        item.setDsTaskInstanceId(ne.getDsTaskInstanceId());
        item.setStartTime(ne.getStartTime());
        item.setEndTime(ne.getEndTime());
        item.setDurationMs(ne.getDurationMs());
        item.setErrorMessage(ne.getErrorMessage());
        item.setOutputInfo(ne.getOutputInfo());
        item.setSyncJobHistoryId(ne.getSyncJobHistoryId());
        return item;
    }

    /**
     * 性能优化：批量查询当前页 execution 对应的 dag（EngineeringDagApi.batchGet）。
     * 先读缓存，miss 的 id 一次性批量远程查询，并把结果（含 null）写回缓存。
     */
    private Map<Long, DagInfo> resolveDagBatch(List<DagExecutionInfo> executions) {
        Map<Long, DagInfo> result = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (DagExecutionInfo ex : executions) {
            Long dagId = ex.getDagId();
            if (dagId == null) continue;
            DagInfo cached = dagCache.getIfPresent(dagId);
            if (cached != null) {
                result.put(dagId, cached);
            } else if (!dagCache.asMap().containsKey(dagId)) {
                // 缓存中既没有命中，也没有缓存过 null，才需要远程查询
                missingIds.add(dagId);
            }
        }
        if (!missingIds.isEmpty()) {
            IdsRequest request = new IdsRequest();
            request.setIds(missingIds);
            Map<Long, DagInfo> dags = RemoteCalls.execute("engineering.dag.batch-get", () -> {
                Result<Map<Long, DagInfo>> batchResult = dagApi.batchGet(request);
                return batchResult == null || batchResult.data() == null ? Map.of() : batchResult.data();
            }, Map.of());
            for (Map.Entry<Long, DagInfo> entry : dags.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    dagCache.put(entry.getKey(), entry.getValue());
                    result.put(entry.getKey(), entry.getValue());
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

    /**
     * DS 流程实例是否到达终态（5=STOP / 6=FAILURE / 7=SUCCESS / 9=KILL）。
     * null（查询失败或不支持）按非终态处理，绝不误标。
     */
    private boolean isDsTerminalState(Integer dsState) {
        if (dsState == null) return false;
        return dsState == DS_SUCCESS || dsState == DS_FAILURE || dsState == DS_STOP || dsState == DS_KILL;
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

        /**
         * 拉取 DS 流程实例状态 code（与 mapDsState 约定一致：5/6/7/9 为终态）。
         * 默认返回 null（不支持），sync 端只在本地有 WAITING 节点时调用，
         * 用于「DS 流程实例终态后把从未运行的 WAITING 节点标 SKIPPED」的兜底。
         */
        default Integer fetchWorkflowState(Long dsProjectCode, Long dsProcessInstanceId) {
            return null;
        }
    }

    /**
     * Sprint 3 P1-2：SYNC 节点历史查询器 SPI
     * 调用方实现：data-nest-job 查 sync_job_history 表
     */
    public interface SyncJobHistoryFetcher {
        SyncHistoryResult fetchLatestHistory(Long syncJobId);

        /**
         * 负数耗时修复：只接受 end_time 不早于 nodeStartTime（node_execution.start_time）的 history，
         * 避免拿到上一轮运行的 history 算出负耗时。取不到合格 history 返回 null，本轮跳过收尾。
         * 默认退化为不过滤的单参版本；调用方应覆盖此方法实现过滤。
         */
        default SyncHistoryResult fetchLatestHistory(Long syncJobId, LocalDateTime nodeStartTime) {
            return fetchLatestHistory(syncJobId);
        }
    }

    public record SyncHistoryResult(String status, LocalDateTime endTime, String errorMessage, String outputInfo,
                                    Long historyId) {
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
