package com.datanest.task.core.service;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.dto.DagExecutionFinalizeRequest;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.DagInfo;
import com.datanest.engineering.api.dto.DagNodeInfo;
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
 * 职责：拉取所有 RUNNING 的 dag_execution，经调用方注入的 PowerJob 工作流实例查询器同步状态
 * 不依赖调度引擎 SDK / OpenAPI（HTTP 细节由调用方实现的 fetcher SPI 封装）
 *
 * 调用方：data-nest-job 的 DagExecutionSyncHandler（PowerJob 调度，默认每 30 秒，自适应缩短）
 *
 * 微服务化 3.3：dag/dag_execution/node_execution 读写全部经 Feign 调 app-engineering
 * （EngineeringDagApi / EngineeringDagExecutionApi），远程失败经 RemoteCalls 降级，
 * 本轮按无数据处理，下一轮重试；终态收尾调 finalize 端点，DAG 完成告警副作用由
 * engineering 端内置触发（不再走本地监听器）。
 *
 * P3 调度引擎迁移（DolphinScheduler → PowerJob）：
 * - fetcher SPI 由 DS task-instances 查询换成 PowerJob fetchWfInstanceInfo 快照查询；
 * - 节点匹配废弃「节点名 / 节点名_节点ID后8位」，改为 nodeId == dag_node.powerjob_node_id 精确匹配
 *   （PEWorkflowDAG 的 nodeId 是服务端 workflow_node_info.id，非 dag_node.id）；
 * - 存量 DS 执行（dsProcessInstanceId 非空且 powerjobWfInstanceId 为空）一次性兜底标 FAILED。
 *
 * Sprint 3 修复：
 * - P1-2：SYNC 节点在 RUNNING 时反查 sync_job_history 收尾
 * - 性能3：分页查询 + 批量 updateById
 * - 性能7：复用 nodeByUuid.values()，避免重复查库
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

    /** 性能优化：dag 节点定义本地缓存（dag_node.powerjob_node_id ↔ node_execution.node_id 桥接用，与 dagCache 同策略） */
    private final Cache<Long, List<DagNodeInfo>> dagNodesCache = Caffeine.newBuilder()
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
     * 决策：调用方提供 PowerJob 工作流实例查询器（解耦 OpenAPI 依赖，task-core 不依赖调度配置）
     * 决策：调用方提供 SYNC 节点历史查询器（Sprint 3 P1-2）
     * 决策 Sprint3-Fix4：调用方可提供 SyncJobMutexReleaser，sync_job_history 显示 SUCCESS/FAILED 时
     *                    释放 syncJobId 互斥锁（让 callback 拿锁后不 finally 释放的锁得以回收）
     */
    public SyncResult syncRunningExecutions(PowerJobWfInstanceFetcher fetcher, SyncJobHistoryFetcher syncFetcher,
                                            SyncJobMutexReleaser mutexReleaser) {
        if (fetcher == null) {
            throw new IllegalArgumentException("PowerJobWfInstanceFetcher 不能为空");
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
                // 静默期：刚 trigger 的 execution 给 PowerJob 启动时间，避免无效同步
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
     * 同步单个 dag_execution
     * @return true 表示有状态变更
     */
    private boolean syncOne(DagExecutionInfo execution, DagInfo dag, PowerJobWfInstanceFetcher fetcher,
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

        Long wfInstanceId = execution.getPowerjobWfInstanceId();
        if (wfInstanceId == null) {
            if (execution.getDsProcessInstanceId() != null) {
                // 迁移切流兜底（一次性）：部署窗口确保无 RUNNING 中 DAG；仍存在的存量 DS 执行
                // 在 PowerJob 侧没有对应实例，直接标 FAILED，避免永久 RUNNING 卡死
                markLegacyExecutionFailed(execution);
                return true;
            }
            // PowerJob 工作流实例 ID 尚未回写（trigger 后异步回写窗口），本轮跳过
            return false;
        }

        // 1. 拉 PowerJob 工作流实例快照（节点状态 + 实例整体状态，一次调用）
        WfInstanceSnapshot snapshot = fetcher.fetchWfInstance(wfInstanceId);
        if (snapshot == null || snapshot.nodes() == null) {
            return false;
        }

        // 2. 节点匹配：PowerJob dag.nodes[].nodeId = 服务端 workflow_node_info.id，
        //    对应平台 dag_node.powerjob_node_id；node_execution.node_id 存的是前端 UUID
        //    （dag_node.node_id），经 dag 节点定义桥接后精确匹配（废弃 DS 时代的节点名匹配逻辑）
        List<NodeExecutionInfo> nodeList = listExecutionNodes(execution.getId());
        Map<Long, NodeExecutionInfo> nodeByPowerjobNodeId = matchNodesByPowerjobNodeId(execution.getDagId(), nodeList);

        boolean changed = false;
        // 性能3：累积变更后批量 update
        List<NodeExecutionInfo> updatedNodes = new ArrayList<>();

        for (WfNodeStatus node : snapshot.nodes()) {
            NodeExecutionInfo ne = nodeByPowerjobNodeId.get(node.nodeId());
            if (ne == null) continue;
            boolean nodeChanged = false;
            // 回写 PowerJob 任务实例 ID（替代原 ds_task_instance_id 回填）
            if (node.instanceId() != null && !node.instanceId().equals(ne.getPowerjobInstanceId())) {
                ne.setPowerjobInstanceId(node.instanceId());
                nodeChanged = true;
            }
            // status 为 null 表示该节点尚未运行（PEWorkflowDAG 初始拷贝），不动本地状态，
            // 避免把从未运行的 WAITING 节点误标 RUNNING（SYNC 节点会被误判去反查 history）
            String newStatus = mapPowerJobNodeStatus(node.status());
            if (newStatus != null) {
                // 终态保护：已被 callback/其它路径置为终态（SKIPPED/SUCCESS/FAILED/TERMINATED）的节点
                // 不允许被调度侧状态回写覆盖（条件分支 gate 的 SKIPPED 会被对侧的 SUCCESS 误复活）。
                // 远程化后 sync 与节点状态写入存在跨服务时序窗口，终态不可逆是安全不变量。
                if (!isTerminalStatus(ne.getStatus()) && !newStatus.equals(ne.getStatus())) {
                    ne.setStatus(newStatus);
                    nodeChanged = true;
                }
                // 节点起止时间优先保留 callback / sync_history 写入的毫秒精度记录；
                // 仅在本地没有时才用 PowerJob 返回的秒级时间兜底。
                if (ne.getStartTime() == null && node.startTime() != null) {
                    LocalDateTime t = parseNodeTime(node.startTime());
                    if (t != null) {
                        ne.setStartTime(t);
                        nodeChanged = true;
                    }
                }
                if (ne.getEndTime() == null && node.endTime() != null) {
                    LocalDateTime t = parseNodeTime(node.endTime());
                    if (t != null) {
                        ne.setEndTime(t);
                        nodeChanged = true;
                    }
                }
                // 节点耗时保留毫秒精度：只有本地没有耗时且起止时间都已存在时才兜底计算
                if (ne.getDurationMs() == null && ne.getStartTime() != null && ne.getEndTime() != null) {
                    long duration = Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis();
                    ne.setDurationMs(duration);
                    nodeChanged = true;
                }
            }
            if (nodeChanged) {
                updatedNodes.add(ne);
                changed = true;
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

        // 3.5 实例节点数错乱修复：PowerJob 工作流实例到达终态后，本地仍 WAITING 的节点（因上游失败
        // 等原因从未运行）标记为 SKIPPED。只在工作流终态分支做，运行中实例绝不误标。
        boolean hasWaiting = false;
        for (NodeExecutionInfo ne : nodeList) {
            if ("WAITING".equalsIgnoreCase(ne.getStatus())) {
                hasWaiting = true;
                break;
            }
        }
        if (hasWaiting && isPowerJobWfTerminal(snapshot.wfStatus())) {
            for (NodeExecutionInfo ne : nodeList) {
                if ("WAITING".equalsIgnoreCase(ne.getStatus())) {
                    ne.setStatus("SKIPPED");
                    ne.setEndTime(LocalDateTime.now());
                    updatedNodes.add(ne);
                    changed = true;
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
     * 迁移切流兜底（一次性分支）：存量 DS 执行（dsProcessInstanceId 非空、powerjobWfInstanceId 为空）
     * 在 PowerJob 侧没有可同步的实例，直接标 FAILED 并在 error_message 注明原因。
     */
    private void markLegacyExecutionFailed(DagExecutionInfo execution) {
        logger.warn("存量 DS 执行在迁移切流后直接标记 FAILED: executionId={}, dsProcessInstanceId={}",
                execution.getId(), execution.getDsProcessInstanceId());
        LocalDateTime endTime = LocalDateTime.now();
        DagExecutionFinalizeRequest request = new DagExecutionFinalizeRequest();
        request.setStatus("FAILED");
        request.setEndTime(endTime);
        if (execution.getStartTime() != null) {
            request.setDurationMs(Duration.between(execution.getStartTime(), endTime).toMillis());
        }
        request.setErrorMessage("调度引擎迁移切流（DolphinScheduler → PowerJob），存量 RUNNING 执行一次性标记失败");
        RemoteCalls.execute("engineering.dag-execution.finalize",
                () -> dagExecutionApi.finalizeExecution(execution.getId(), request));
        execution.setStatus("FAILED");
        execution.setEndTime(endTime);
        execution.setDurationMs(request.getDurationMs());
    }

    /**
     * 建立「PowerJob 节点 ID（服务端 workflow_node_info.id）→ node_execution」映射。
     * PowerJob PEWorkflowDAG 的 nodeId 是 workflow_node_info.id，并非平台 dag_node.id；
     * dag_node.powerjob_node_id 在同步 DAG 到 PowerJob 时回写该值，node_execution.node_id
     * 是前端 UUID，两者经 dag 节点定义（DagNodeInfo.powerjobNodeId ↔ DagNodeInfo.nodeId）桥接，精确匹配。
     */
    private Map<Long, NodeExecutionInfo> matchNodesByPowerjobNodeId(Long dagId, List<NodeExecutionInfo> nodeList) {
        Map<Long, NodeExecutionInfo> result = new HashMap<>();
        if (dagId == null || nodeList.isEmpty()) {
            return result;
        }
        Map<String, NodeExecutionInfo> nodeByUuid = new HashMap<>();
        for (NodeExecutionInfo ne : nodeList) {
            if (ne.getNodeId() != null) {
                nodeByUuid.put(ne.getNodeId(), ne);
            }
        }
        for (DagNodeInfo dagNode : listDagNodes(dagId)) {
            // powerjob_node_id 为空（未同步到 PowerJob / 存量数据）的节点无法匹配，跳过
            if (dagNode.getPowerjobNodeId() == null) continue;
            NodeExecutionInfo ne = nodeByUuid.get(dagNode.getNodeId());
            if (ne != null) {
                result.put(dagNode.getPowerjobNodeId(), ne);
            }
        }
        return result;
    }

    /** DAG 节点定义查询（本地缓存 5 分钟；远程失败降级空列表，本轮按无匹配处理，下一轮重试） */
    private List<DagNodeInfo> listDagNodes(Long dagId) {
        List<DagNodeInfo> cached = dagNodesCache.getIfPresent(dagId);
        if (cached != null) {
            return cached;
        }
        List<DagNodeInfo> nodes = RemoteCalls.execute("engineering.dag.nodes", () -> {
            Result<List<DagNodeInfo>> result = dagApi.listNodes(dagId);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
        dagNodesCache.put(dagId, nodes);
        return nodes;
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
        // 与原 DS 语义保持一致：DAG 结束时间取所有节点 endTime 的最大值
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
        item.setPowerjobInstanceId(ne.getPowerjobInstanceId());
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

    /**
     * PowerJob 任务实例状态 → 平台节点状态（tech.powerjob.common.enums.InstanceStatus）：
     * 1(WAITING_DISPATCH)/2(WAITING_WORKER_RECEIVE)/3(RUNNING) → RUNNING；
     * 5(SUCCEED) → SUCCESS；4(FAILED) → FAILED；9(CANCELED)/10(STOPPED) → TERMINATED。
     * null（节点尚未运行，PEWorkflowDAG 初始拷贝）返回 null，调用方跳过该节点的状态回写。
     */
    private String mapPowerJobNodeStatus(Integer status) {
        if (status == null) return null;
        return switch (status) {
            case PJ_INSTANCE_SUCCEED -> "SUCCESS";
            case PJ_INSTANCE_FAILED -> "FAILED";
            case PJ_INSTANCE_CANCELED, PJ_INSTANCE_STOPPED -> "TERMINATED";
            default -> "RUNNING";   // 1/2/3 及未知状态都视为运行中
        };
    }

    /**
     * PowerJob 工作流实例是否到达终态（3=FAILED / 4=SUCCEED / 10=STOPPED）。
     * null（查询失败或字段缺失）按非终态处理，绝不误标。
     */
    private boolean isPowerJobWfTerminal(Integer wfStatus) {
        if (wfStatus == null) return false;
        return wfStatus == PJ_WF_FAILED || wfStatus == PJ_WF_SUCCEED || wfStatus == PJ_WF_STOPPED;
    }

    /** 解析 PowerJob 节点时间（"yyyy-MM-dd HH:mm:ss"；未运行为 "N/A"，解析失败返回 null） */
    private LocalDateTime parseNodeTime(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s.replace(" ", "T"));
        } catch (Exception e) {
            return null;
        }
    }

    // =================== SPI ===================

    /** PowerJob 任务实例状态码（tech.powerjob.common.enums.InstanceStatus） */
    private static final int PJ_INSTANCE_FAILED = 4;
    private static final int PJ_INSTANCE_SUCCEED = 5;
    private static final int PJ_INSTANCE_CANCELED = 9;
    private static final int PJ_INSTANCE_STOPPED = 10;

    /** PowerJob 工作流实例状态码（tech.powerjob.common.enums.WorkflowInstanceStatus） */
    private static final int PJ_WF_FAILED = 3;
    private static final int PJ_WF_SUCCEED = 4;
    private static final int PJ_WF_STOPPED = 10;

    /**
     * PowerJob 工作流实例查询器 SPI（接口）
     * 调用方实现：data-nest-job 经 common 的 PowerJobWorkflowClient.fetchWfInstanceInfo 拉取并解析
     * 决策：解耦 PowerJob OpenAPI 依赖，task-core 不依赖调度配置
     */
    public interface PowerJobWfInstanceFetcher {
        /**
         * 拉取 PowerJob 工作流实例快照（节点状态 + 实例整体状态）。
         * 查询失败返回 null，本轮按无数据处理，下一轮重试。
         */
        WfInstanceSnapshot fetchWfInstance(Long wfInstanceId);
    }

    /**
     * PowerJob 工作流实例快照：wfStatus 为实例整体状态（3/4/10 为终态），nodes 为 PEWorkflowDAG 节点列表。
     */
    public record WfInstanceSnapshot(Integer wfStatus, List<WfNodeStatus> nodes) {
    }

    /**
     * PowerJob 工作流节点状态（PEWorkflowDAG.Node 子集）：
     * nodeId = PowerJob 服务端 workflow_node_info.id（对应平台 dag_node.powerjob_node_id）；instanceId = PowerJob 任务实例 ID；
     * status 为 InstanceStatus code（null 表示节点尚未运行）；
     * startTime/endTime 为 "yyyy-MM-dd HH:mm:ss" 字符串（未运行为 "N/A"，解析失败按 null 处理）。
     */
    public record WfNodeStatus(Long nodeId, Long instanceId, Integer status,
                               String startTime, String endTime) {
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
}
