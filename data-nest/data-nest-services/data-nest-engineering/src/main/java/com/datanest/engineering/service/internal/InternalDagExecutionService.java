package com.datanest.engineering.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.alert.api.AlertApi;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.PageResult;
import com.datanest.engineering.api.dto.DagExecutionFinalizeRequest;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.DagEdgeInfo;
import com.datanest.engineering.api.dto.EnsureDagExecutionRequest;
import com.datanest.engineering.api.dto.NodeExecutionBatchUpdateRequest;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import com.datanest.engineering.api.dto.NodeExecutionMarkRequest;
import com.datanest.engineering.api.dto.NodeLogAppendRequest;
import com.datanest.engineering.entity.Dag;
import com.datanest.engineering.entity.DagEdge;
import com.datanest.engineering.entity.DagExecution;
import com.datanest.engineering.entity.DagNode;
import com.datanest.engineering.entity.NodeExecution;
import com.datanest.engineering.entity.NodeExecutionLog;
import com.datanest.engineering.mapper.DagEdgeMapper;
import com.datanest.engineering.mapper.DagExecutionMapper;
import com.datanest.engineering.mapper.DagMapper;
import com.datanest.engineering.mapper.DagNodeMapper;
import com.datanest.engineering.mapper.NodeExecutionLogMapper;
import com.datanest.engineering.mapper.NodeExecutionMapper;
import com.datanest.task.core.service.DagEdgeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DAG 执行记录域内部接口服务（engineering 归属表 dag_execution / node_execution / node_execution_log）。
 * <p>
 * 收割、清理、乐观锁批量更新语义逐一对照 task-core 原实现
 * （原卡死收割服务 / DagExecutionHistoryCleanupHandler / NodeExecutionMapper.updateBatch）；
 * finalize 副作用搬迁自 task-core 原 DAG 完成监听器（进程内直接 Feign 调 app-alert）。
 */
@Service
public class InternalDagExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(InternalDagExecutionService.class);

    private static final int DEFAULT_STUCK_MINUTES = 120;

    private static final int DEFAULT_RETAIN_DAYS = 30;

    /** 清理批次大小（与 DagExecutionHistoryCleanupHandler.BATCH_SIZE 一致） */
    private static final int CLEANUP_BATCH_SIZE = 500;

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final NodeExecutionLogMapper nodeExecutionLogMapper;
    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final AlertApi alertApi;

    /** ensureExecutionByWfInstance 的进程内并发锁（按 wfInstanceId 双检，对齐原 worker 侧 EXECUTION_LOCKS 语义） */
    private final Map<Long, Object> ensureExecutionLocks = new ConcurrentHashMap<>();

    public InternalDagExecutionService(DagExecutionMapper dagExecutionMapper,
                                       NodeExecutionMapper nodeExecutionMapper,
                                       NodeExecutionLogMapper nodeExecutionLogMapper,
                                       DagMapper dagMapper,
                                       DagNodeMapper dagNodeMapper,
                                       DagEdgeMapper dagEdgeMapper,
                                       AlertApi alertApi) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.nodeExecutionLogMapper = nodeExecutionLogMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.alertApi = alertApi;
    }

    // ==================== 执行实例 ====================

    public PageResult<DagExecutionInfo> listRunning(int page, int pageSize) {
        long pageNo = Math.max(1, page);
        long size = Math.max(1, pageSize);
        IPage<DagExecution> result = dagExecutionMapper.selectPage(
                new Page<>(pageNo, size),
                new QueryWrapper<DagExecution>().eq("status", "RUNNING").orderByAsc("id"));
        return PageResult.of(result.getRecords().stream().map(InternalDagExecutionService::toExecutionInfo).toList(),
                result.getTotal(), pageNo, size);
    }

    public DagExecutionInfo getById(Long id) {
        return toExecutionInfo(dagExecutionMapper.selectById(id));
    }

    /**
     * P3：按 PowerJob 工作流实例补齐执行记录（POST /internal/dag/ensure-execution）。
     * PowerJob cron 直接触发工作流时 DataNest 未显式 trigger，worker 节点 handler 处理首个节点前
     * 经 Feign 调本方法：若该 wfInstanceId 已有 dag_execution 直接返回其 id；
     * 否则创建 dag_execution（triggerType=SCHEDULED，status=RUNNING）+ 全量 WAITING node_execution。
     * 语义对齐原 worker 侧 ensureDagExecution（DS 定时实例补齐），幂等并发控制用进程内按
     * wfInstanceId 加锁双检（原 worker EXECUTION_LOCKS 同款）。
     *
     * @return dagExecutionId
     */
    @Transactional
    public Long ensureExecutionByWfInstance(EnsureDagExecutionRequest request) {
        if (request == null || request.getDagId() == null || request.getWfInstanceId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ensure-execution 缺少 dagId/wfInstanceId");
        }
        Long dagId = request.getDagId();
        Long wfInstanceId = request.getWfInstanceId();
        DagExecution existing = dagExecutionMapper.selectByPowerjobWfInstanceId(wfInstanceId);
        if (existing != null) {
            return existing.getId();
        }
        synchronized (ensureExecutionLocks.computeIfAbsent(wfInstanceId, k -> new Object())) {
            existing = dagExecutionMapper.selectByPowerjobWfInstanceId(wfInstanceId);
            if (existing != null) {
                return existing.getId();
            }
            Dag dag = dagMapper.selectById(dagId);
            if (dag == null) {
                throw new BusinessException(ErrorCode.DAG_NOT_FOUND, "DAG 不存在: " + dagId);
            }
            LocalDateTime now = LocalDateTime.now();
            DagExecution execution = new DagExecution();
            execution.setDagId(dagId);
            execution.setPowerjobWfInstanceId(wfInstanceId);
            execution.setTriggerType("SCHEDULED");
            execution.setStatus("RUNNING");
            execution.setStartTime(now);
            execution.setCreatedAt(now);
            // 边快照：历史视图（run-view）用快照渲染边，避免后续删节点导致历史实例连线丢失
            execution.setEdgeSnapshot(DagEdgeSnapshot.capture(
                    dagEdgeMapper.selectByDagId(dagId).stream().map(InternalDagExecutionService::toEdgeInfo).toList()));
            try {
                dagExecutionMapper.insert(execution);
            } catch (DuplicateKeyException e) {
                // uk_dag_execution_running 部分唯一索引触发：同 DAG 已有 RUNNING（并发 cron 实例或手动触发中）
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "DAG " + dagId + " 当前已有运行中的执行，定时实例执行记录补齐失败: wfInstanceId=" + wfInstanceId);
            }

            List<DagNode> dagNodes = dagNodeMapper.selectByDagId(dagId);
            if (!dagNodes.isEmpty()) {
                List<NodeExecution> nodes = new ArrayList<>(dagNodes.size());
                for (DagNode dagNode : dagNodes) {
                    NodeExecution node = new NodeExecution();
                    node.setId(IdWorker.getId());
                    node.setExecutionId(execution.getId());
                    node.setNodeId(dagNode.getNodeId());
                    node.setNodeName(dagNode.getNodeName());
                    node.setNodeType(dagNode.getNodeType());
                    node.setStatus("WAITING");
                    nodes.add(node);
                }
                nodeExecutionMapper.insertBatch(nodes);
            }
            logger.info("为 PowerJob 定时实例补齐执行记录: dagId={}, wfInstanceId={}, executionId={}",
                    dagId, wfInstanceId, execution.getId());
            return execution.getId();
        }
    }

    /** dag_edge 实体 → 边快照 DTO（DagEdgeSnapshot 只接收 DTO） */
    private static DagEdgeInfo toEdgeInfo(DagEdge edge) {
        DagEdgeInfo info = new DagEdgeInfo();
        info.setSourceNodeId(edge.getSourceNodeId());
        info.setTargetNodeId(edge.getTargetNodeId());
        return info;
    }

    /**
     * 终态回写 + DAG 完成副作用：落库后在 engineering 进程内直接调 app-alert
     * 的 dagFinished（原 task-core DAG 完成监听器搬迁，RemoteCalls 容错降级，最终一致）。
     */
    public void finalizeExecution(Long id, DagExecutionFinalizeRequest request) {
        DagExecution execution = dagExecutionMapper.selectById(id);
        if (execution == null) {
            throw new BusinessException(ErrorCode.DAG_EXECUTION_NOT_FOUND);
        }
        execution.setStatus(request.getStatus());
        LocalDateTime endTime = request.getEndTime() != null ? request.getEndTime() : LocalDateTime.now();
        execution.setEndTime(endTime);
        if (request.getErrorMessage() != null) {
            execution.setErrorMessage(request.getErrorMessage());
        }
        if (request.getDurationMs() != null) {
            execution.setDurationMs(request.getDurationMs());
        } else if (execution.getStartTime() != null) {
            execution.setDurationMs(Duration.between(execution.getStartTime(), endTime).toMillis());
        }
        dagExecutionMapper.updateById(execution);

        List<NodeExecution> nodes = nodeExecutionMapper.selectByExecutionId(id);
        fireDagFinished(execution, nodes);
    }

    /** 时间段内 SUCCESS 的执行（质量对账 handler 扫描用），按 start_time 过滤、id 升序 */
    public List<DagExecutionInfo> succeededBetween(LocalDateTime from, LocalDateTime to, int limit) {
        return dagExecutionMapper.selectList(new QueryWrapper<DagExecution>()
                        .eq("status", "SUCCESS")
                        .ge("start_time", from)
                        .le("start_time", to)
                        .orderByAsc("id")
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(InternalDagExecutionService::toExecutionInfo).toList();
    }

    /**
     * 收割卡死 RUNNING 的 dag_execution + node_execution
     * （与原 task-core 收割实现的 dag/node 两部分一致，
     * 不使用整体事务，逐类批量 update 即时提交）。
     *
     * @return 收割的 dag_execution + node_execution 总条数
     */
    public int reapStuckDag(Integer stuckBeforeMinutes) {
        int minutes = stuckBeforeMinutes == null || stuckBeforeMinutes < 1 ? DEFAULT_STUCK_MINUTES : stuckBeforeMinutes;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutes);
        String reason = "DAG 执行卡死收割：RUNNING 超过 " + minutes + " 分钟未结束，判定执行方失联，标记为 FAILED";

        int dagExecutions = 0;
        List<DagExecution> stuck = dagExecutionMapper.selectList(new QueryWrapper<DagExecution>()
                .eq("status", "RUNNING")
                .isNotNull("start_time")
                .lt("start_time", threshold));
        LocalDateTime now = LocalDateTime.now();
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
            dagExecutions++;
        }

        // 孤儿节点兜底：DAG 已结束但节点残留 RUNNING（被收割 DAG 的节点上面已处理，此处幂等）
        int orphanNodes = nodeExecutionMapper.update(null, new UpdateWrapper<NodeExecution>()
                .set("status", "FAILED")
                .set("error_message",
                        "节点执行卡死收割：RUNNING 超过 " + minutes + " 分钟未结束，判定执行方失联，标记为 FAILED")
                .set("end_time", now)
                .eq("status", "RUNNING")
                .isNotNull("start_time")
                .lt("start_time", threshold));
        if (dagExecutions + orphanNodes > 0) {
            logger.info("卡死 DAG 收割完成: dagExecution={}, orphanNodeExecution={}, threshold={}",
                    dagExecutions, orphanNodes, threshold);
        }
        return dagExecutions + orphanNodes;
    }

    /**
     * 清理 N 天前终态执行及其 node_execution（与 DagExecutionHistoryCleanupHandler 一致，
     * selectTerminalsBefore + 删 node_execution + 删 dag_execution，500/批）。
     *
     * @return 删除的 dag_execution 条数
     */
    public int cleanupDagExecutions(Integer retainDays) {
        int days = retainDays == null || retainDays < 1 ? DEFAULT_RETAIN_DAYS : retainDays;
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(days);
        int totalExecutions = 0;
        int totalNodes = 0;
        while (true) {
            List<DagExecution> executions = dagExecutionMapper.selectTerminalsBefore(beforeTime, CLEANUP_BATCH_SIZE);
            if (executions == null || executions.isEmpty()) {
                break;
            }
            for (DagExecution execution : executions) {
                totalNodes += nodeExecutionMapper.delete(
                        new QueryWrapper<NodeExecution>().eq("execution_id", execution.getId()));
            }
            totalExecutions += dagExecutionMapper.delete(new QueryWrapper<DagExecution>()
                    .in("id", executions.stream().map(DagExecution::getId).toList()));
            if (executions.size() < CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        logger.info("DAG 执行历史清理完成: beforeTime={}, deletedExecutions={}, deletedNodes={}",
                beforeTime, totalExecutions, totalNodes);
        return totalExecutions;
    }

    // ==================== 节点执行 ====================

    public List<NodeExecutionInfo> listNodes(Long executionId) {
        return nodeExecutionMapper.selectByExecutionId(executionId)
                .stream().map(InternalDagExecutionService::toNodeInfo).toList();
    }

    /**
     * 批量乐观锁更新：保留 version 冲突语义（WHERE (id, version) 成对匹配、version+1），
     * version 不匹配的行跳过不写；通过回查当前 version（成功行 = 请求 version + 1）得出失败 id 列表。
     */
    public List<Long> batchUpdateNodes(NodeExecutionBatchUpdateRequest request) {
        List<Long> failed = new ArrayList<>();
        if (request.getUpdates() == null || request.getUpdates().isEmpty()) {
            return failed;
        }
        List<NodeExecution> entities = new ArrayList<>(request.getUpdates().size());
        for (NodeExecutionBatchUpdateRequest.UpdateItem item : request.getUpdates()) {
            NodeExecution entity = new NodeExecution();
            entity.setId(item.getId());
            entity.setVersion(item.getVersion());
            entity.setStatus(item.getStatus());
            entity.setPowerjobInstanceId(item.getPowerjobInstanceId());
            entity.setStartTime(item.getStartTime());
            entity.setEndTime(item.getEndTime());
            entity.setDurationMs(item.getDurationMs());
            entity.setErrorMessage(item.getErrorMessage());
            entity.setOutputInfo(item.getOutputInfo());
            entity.setSyncJobHistoryId(item.getSyncJobHistoryId());
            entities.add(entity);
        }
        int updated = nodeExecutionMapper.updateBatch(entities);
        if (updated >= entities.size()) {
            return failed;
        }
        // 回查当前 version 判定哪些行被乐观锁跳过：成功行 version 已被 bump 为 请求值+1
        Map<Long, Integer> currentVersions = new HashMap<>();
        for (NodeExecution current : nodeExecutionMapper.selectBatchIds(
                entities.stream().map(NodeExecution::getId).toList())) {
            currentVersions.put(current.getId(), current.getVersion());
        }
        for (NodeExecutionBatchUpdateRequest.UpdateItem item : request.getUpdates()) {
            Integer current = currentVersions.get(item.getId());
            boolean succeeded = current != null && item.getVersion() != null && current == item.getVersion() + 1;
            if (!succeeded) {
                failed.add(item.getId());
            }
        }
        if (!failed.isEmpty()) {
            logger.warn("node_execution 批量更新存在版本冲突跳过: 期望更新={}, 实际更新={}, failedIds={}",
                    entities.size(), updated, failed);
        }
        return failed;
    }

    /**
     * 节点状态机单点更新：expectedStatus 非空时做条件更新（当前 status 不匹配返回 false）；
     * 可空字段仅在有值时覆盖；乐观锁沿用实体的 @Version（并发 bump 时 updateById 影响 0 行返回 false）。
     */
    public boolean markNode(Long id, NodeExecutionMarkRequest request) {
        NodeExecution entity = nodeExecutionMapper.selectById(id);
        if (entity == null) {
            return false;
        }
        if (request.getExpectedStatus() != null
                && !request.getExpectedStatus().equalsIgnoreCase(entity.getStatus())) {
            return false;
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        if (request.getOutputInfo() != null) {
            entity.setOutputInfo(request.getOutputInfo());
        }
        if (request.getErrorMessage() != null) {
            entity.setErrorMessage(request.getErrorMessage());
        }
        if (request.getDurationMs() != null) {
            entity.setDurationMs(request.getDurationMs());
        }
        if (request.getSyncJobId() != null) {
            entity.setSyncJobId(request.getSyncJobId());
        }
        if (request.getSyncJobHistoryId() != null) {
            entity.setSyncJobHistoryId(request.getSyncJobHistoryId());
        }
        if (request.getStartTime() != null) {
            entity.setStartTime(request.getStartTime());
        }
        if (request.getEndTime() != null) {
            entity.setEndTime(request.getEndTime());
        }
        return nodeExecutionMapper.updateById(entity) > 0;
    }

    /** DAG 被 stop 时把未结束子节点标 SKIPPED（markSkippedByExecutionId 语义） */
    public int markNodesSkipped(Long executionId) {
        return nodeExecutionMapper.markSkippedByExecutionId(executionId, LocalDateTime.now());
    }

    /** RUNNING 节点（含 dagId，join 在服务端做，超时告警扫描用） */
    public List<NodeExecutionInfo> runningWithDag(int limit) {
        return nodeExecutionMapper.selectRunningWithDagId(Math.max(1, limit))
                .stream().map(InternalDagExecutionService::toNodeInfo).toList();
    }

    /** 按 syncJobId 查未结束（RUNNING/WAITING）节点执行 */
    public List<NodeExecutionInfo> runningBySyncJob(Long syncJobId) {
        return nodeExecutionMapper.selectRunningListBySyncJobId(syncJobId)
                .stream().map(InternalDagExecutionService::toNodeInfo).toList();
    }

    /**
     * 追加节点日志：服务端按 executionId + nodeId 续号，一次事务批量插入
     * （与原 task-core 日志服务 saveLogs 一致，逐行 insert）。
     */
    @Transactional
    public void appendNodeLogs(Long nodeExecutionId, NodeLogAppendRequest request) {
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            return;
        }
        NodeExecution nodeExecution = nodeExecutionMapper.selectById(nodeExecutionId);
        if (nodeExecution == null) {
            throw new BusinessException(ErrorCode.DAG_EXECUTION_NOT_FOUND);
        }
        Long executionId = request.getExecutionId() != null ? request.getExecutionId() : nodeExecution.getExecutionId();
        String nodeId = nodeExecution.getNodeId();
        Long count = nodeExecutionLogMapper.selectCount(new QueryWrapper<NodeExecutionLog>()
                .eq("execution_id", executionId)
                .eq("node_id", nodeId));
        int lineNum = (int) (count == null ? 0 : count) + 1;
        LocalDateTime now = LocalDateTime.now();
        for (NodeLogAppendRequest.Entry entry : request.getEntries()) {
            NodeExecutionLog log = new NodeExecutionLog();
            log.setExecutionId(executionId);
            log.setNodeId(nodeId);
            log.setLevel(entry.getLevel() == null ? "INFO" : entry.getLevel());
            log.setMessage(entry.getMessage());
            log.setLineNum(lineNum++);
            log.setCreatedAt(now);
            nodeExecutionLogMapper.insert(log);
        }
    }

    // ==================== DAG 完成副作用（原 task-core DAG 完成监听器搬迁） ====================

    private void fireDagFinished(DagExecution execution, List<NodeExecution> nodes) {
        if (execution == null || nodes == null) {
            return;
        }
        RemoteCalls.execute("alert.dagFinished", () -> {
            com.datanest.alert.api.dto.DagFinishedRequest request = new com.datanest.alert.api.dto.DagFinishedRequest();
            com.datanest.alert.api.dto.DagExecutionInfo executionInfo = new com.datanest.alert.api.dto.DagExecutionInfo();
            executionInfo.setId(execution.getId());
            executionInfo.setDagId(execution.getDagId());
            executionInfo.setStatus(execution.getStatus());
            executionInfo.setStartTime(execution.getStartTime());
            executionInfo.setEndTime(execution.getEndTime());
            request.setExecution(executionInfo);
            // NodeExecutionInfo 需要 dagId，实体上没有，从 execution 取
            request.setNodes(nodes.stream().map(n -> {
                com.datanest.alert.api.dto.NodeExecutionInfo nodeInfo = new com.datanest.alert.api.dto.NodeExecutionInfo();
                nodeInfo.setId(n.getId());
                nodeInfo.setExecutionId(n.getExecutionId());
                nodeInfo.setDagId(execution.getDagId());
                nodeInfo.setNodeId(n.getNodeId());
                nodeInfo.setNodeName(n.getNodeName());
                nodeInfo.setNodeType(n.getNodeType());
                nodeInfo.setStatus(n.getStatus());
                nodeInfo.setErrorMessage(n.getErrorMessage());
                nodeInfo.setStartTime(n.getStartTime());
                nodeInfo.setEndTime(n.getEndTime());
                return nodeInfo;
            }).toList());
            alertApi.dagFinished(request);
        });
    }

    // ==================== 映射 ====================

    static DagExecutionInfo toExecutionInfo(DagExecution entity) {
        if (entity == null) {
            return null;
        }
        DagExecutionInfo info = new DagExecutionInfo();
        info.setId(entity.getId());
        info.setDagId(entity.getDagId());
        info.setPowerjobWfInstanceId(entity.getPowerjobWfInstanceId());
        info.setTriggerType(entity.getTriggerType());
        info.setStatus(entity.getStatus());
        info.setStartTime(entity.getStartTime());
        info.setEndTime(entity.getEndTime());
        info.setDurationMs(entity.getDurationMs());
        info.setCreatedBy(entity.getCreatedBy());
        info.setCreatedAt(entity.getCreatedAt());
        info.setEdgeSnapshot(entity.getEdgeSnapshot());
        info.setErrorMessage(entity.getErrorMessage());
        info.setResolvedParams(entity.getResolvedParams());
        return info;
    }

    static NodeExecutionInfo toNodeInfo(NodeExecution entity) {
        if (entity == null) {
            return null;
        }
        NodeExecutionInfo info = new NodeExecutionInfo();
        info.setId(entity.getId());
        info.setExecutionId(entity.getExecutionId());
        info.setDagId(entity.getDagId());
        info.setNodeId(entity.getNodeId());
        info.setNodeName(entity.getNodeName());
        info.setNodeType(entity.getNodeType());
        info.setStatus(entity.getStatus());
        info.setPowerjobInstanceId(entity.getPowerjobInstanceId());
        info.setSyncJobId(entity.getSyncJobId());
        info.setSyncJobHistoryId(entity.getSyncJobHistoryId());
        info.setStartTime(entity.getStartTime());
        info.setEndTime(entity.getEndTime());
        info.setDurationMs(entity.getDurationMs());
        info.setErrorMessage(entity.getErrorMessage());
        info.setOutputInfo(entity.getOutputInfo());
        info.setVersion(entity.getVersion());
        return info;
    }
}
