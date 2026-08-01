package com.datanest.engineering.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.model.Result;
import com.datanest.engineering.service.SyncJobService;
import com.datanest.engineering.service.SyncNodeMutexService;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.DagMapper;
import com.datanest.task.core.mapper.DagNodeMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.datanest.task.core.service.DorisSqlExecutor;
import com.datanest.task.core.service.MetadataRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DAG 节点内部回调接口（DS worker → engineering）
 * 决策 ADR-S3-008：开发阶段不鉴权，依赖 Docker 网络隔离
 * 决策 ADR-S3-FJ：序列化使用 fastjson2
 * 路由：/dev/internal/{sql,sync,unknown}/callback
 *
 * Sprint 3 修复：
 * - P0-1：executionId 语义统一为 DS processInstanceId，通过 selectByDsProcessInstanceId 反查 DataNest dag_execution
 * - P0-2：handleSyncNode 加 SyncNodeMutexService 互斥
 * - P1-2：SYNC 节点标 RUNNING 不标 SUCCESS，存 sync_job_id 由 DagExecutionSyncService 收尾
 */
@RestController
@RequestMapping("/dev/internal")
public class DagNodeCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeCallbackController.class);

    /** 按 DS processInstanceId 加锁，防止同一实例的并发回调重复创建 dag_execution */
    private static final ConcurrentHashMap<Long, Object> EXECUTION_LOCKS = new ConcurrentHashMap<>();

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final MetadataRegistrationService metadataRegistrationService;
    private final SyncJobService syncJobService;
    private final SyncNodeMutexService syncNodeMutexService;

    public DagNodeCallbackController(DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                                     DagMapper dagMapper, DagNodeMapper dagNodeMapper,
                                     DorisSqlExecutor dorisSqlExecutor,
                                     MetadataRegistrationService metadataRegistrationService,
                                     SyncJobService syncJobService,
                                     SyncNodeMutexService syncNodeMutexService) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.metadataRegistrationService = metadataRegistrationService;
        this.syncJobService = syncJobService;
        this.syncNodeMutexService = syncNodeMutexService;
    }

    @PostMapping("/sql/callback")
    public Result<Map<String, Integer>> sqlCallback(@RequestBody Map<String, Object> body) {
        return handleSqlNode(body);
    }

    @PostMapping("/sync/callback")
    public Result<Map<String, Integer>> syncCallback(@RequestBody Map<String, Object> body) {
        return handleSyncNode(body);
    }

    @PostMapping("/unknown/callback")
    public Result<Map<String, Integer>> unknownCallback(@RequestBody Map<String, Object> body) {
        return error("未知节点类型", 400);
    }

    /**
     * Sprint 3 P0-1：executionId 是 DS processInstanceId（来自 ${system.workflow.instance.id} 变量）
     * 用它反查 DataNest 的 dag_execution.id，再去查 node_execution
     */
    private Result<Map<String, Integer>> handleSqlNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        String sqlContent = stringOf(body.get("sqlContent"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        Long dagId = longOf(body.get("dagId"));
        if (nodeId == null || sqlContent == null) {
            return error("缺少 nodeId / sqlContent", 400);
        }
        NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
        if (lookup.nodeExecution == null) {
            logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
            return error("node execution not found", 404);
        }
        NodeExecution ne = lookup.nodeExecution;
        ne.setStatus("RUNNING");
        ne.setStartTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);

        try {
            int affected = dorisSqlExecutor.execute(sqlContent);
            ne.setOutputInfo("{\"affectedRows\":" + affected + "}");
            try {
                List<String> registered = metadataRegistrationService.registerFromSql(sqlContent, currentUserId());
                if (!registered.isEmpty()) {
                    ne.setOutputInfo(ne.getOutputInfo() + ",\"registeredTables\":" + JSON.toJSONString(registered));
                }
            } catch (Exception e) {
                logger.warn("元数据注册失败（不影响 SQL 执行结果）: {}", e.getMessage());
            }
            ne.setStatus("SUCCESS");
            ne.setEndTime(LocalDateTime.now());
            ne.setDurationMs(java.time.Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis());
            nodeExecutionMapper.updateById(ne);
            return success(affected);
        } catch (BusinessException e) {
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            nodeExecutionMapper.updateById(ne);
            return error(e.getMessage(), 500);
        } catch (Exception e) {
            logger.error("SQL 节点执行失败: nodeId={}", nodeId, e);
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            nodeExecutionMapper.updateById(ne);
            return error(e.getMessage(), 500);
        }
    }

    /**
     * Sprint 3 P0-2 + P1-2 + Sprint3-Fix4：
     * - P0-2：加 SyncNodeMutexService 互斥，同一 syncJobId 同一时刻只能一个执行实例
     * - P1-2：标 RUNNING（不标 SUCCESS），存 sync_job_id，由 DagExecutionSyncService 收尾
     * - Fix4：拿锁后**不**finally 释放（锁在 callback 立刻释放会让 P0-2 互斥窗口太短测不到冲突），
     *         锁由 DagExecutionSyncService 检测 sync_job_history 跑完后通过 SPI 释放。
     *         TTL 6h 兜底防死锁。
     */
    private Result<Map<String, Integer>> handleSyncNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        Long dagId = longOf(body.get("dagId"));
        Object syncJobObj = body.get("syncJob");
        if (nodeId == null || syncJobObj == null) {
            return error("缺少 nodeId / syncJob", 400);
        }
        Long syncJobId = longOf(((Map<?, ?>) syncJobObj).get("id"));
        if (syncJobId == null) {
            return error("syncJob.id 缺失", 400);
        }

        // P0-2：互斥锁（拿不到抛 DAG_ALREADY_RUNNING，DS 那边按 HTTP 5xx 处理会重试）
        String lockToken = syncNodeMutexService.tryLock(syncJobId);
        try {
            NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
            if (lookup.nodeExecution == null) {
                logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
                // 回调找不到记录时立即释放锁，避免阻塞后续触发
                syncNodeMutexService.unlock(syncJobId, lockToken);
                return error("node execution not found", 404);
            }
            NodeExecution ne = lookup.nodeExecution;
            ne.setStatus("RUNNING");
            ne.setStartTime(LocalDateTime.now());
            // P1-2：存 sync_job_id 给 DagExecutionSyncService 收尾
            ne.setSyncJobId(syncJobId);
            nodeExecutionMapper.updateById(ne);

            // 触发 XXL-JOB（异步；本方法返回时 sync 不一定跑完）
            syncJobService.execute(syncJobId);
            // 注意：这里不标 SUCCESS！由 DagExecutionSyncService 根据 sync_job_history 收尾
            return success(0);
        } catch (BusinessException e) {
            // callback 内部异常时立即释放锁，避免 6h TTL 阻塞
            syncNodeMutexService.unlock(syncJobId, lockToken);
            throw e;
        } catch (Exception e) {
            logger.error("同步节点触发失败: nodeId={}, syncJobId={}", nodeId, syncJobId, e);
            // callback 内部异常时立即释放锁，避免 6h TTL 阻塞
            syncNodeMutexService.unlock(syncJobId, lockToken);
            return error(e.getMessage(), 500);
        }
        // 正常路径：锁由 DagExecutionSyncService SPI 收尾时释放
    }

    /**
     * Sprint 3 P0-1：通过 DS processInstanceId 反查 dag_execution，再按 nodeId 查 node_execution。
     * 若 DS 是定时调度直接触发（DataNest 未显式 trigger），则自动补齐 dag_execution 与 node_execution。
     */
    private NodeExecutionLookup resolveNodeExecution(String nodeId, Long dagId, Long dsProcessInstanceId) {
        if (dsProcessInstanceId == null) {
            return new NodeExecutionLookup(null);
        }
        DagExecution dagExecution = dagExecutionMapper.selectByDsProcessInstanceId(dsProcessInstanceId);
        if (dagExecution == null && dagId != null) {
            dagExecution = ensureDagExecution(dagId, dsProcessInstanceId);
        }
        if (dagExecution == null) {
            return new NodeExecutionLookup(null);
        }
        NodeExecution ne = nodeExecutionMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NodeExecution>()
                                .eq("execution_id", dagExecution.getId()).eq("node_id", nodeId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        return new NodeExecutionLookup(ne);
    }

    /**
     * 为 DS 直接触发的流程实例自动创建 DataNest 执行记录（幂等，按 dsProcessInstanceId 加锁）。
     * 如果同一 DAG 已有 RUNNING 记录（uk_dag_execution_running），则放弃创建并返回 null。
     */
    private DagExecution ensureDagExecution(Long dagId, Long dsProcessInstanceId) {
        DagExecution existing = dagExecutionMapper.selectByDsProcessInstanceId(dsProcessInstanceId);
        if (existing != null) return existing;
        if (dagId == null) return null;

        Object lock = EXECUTION_LOCKS.computeIfAbsent(dsProcessInstanceId, k -> new Object());
        synchronized (lock) {
            existing = dagExecutionMapper.selectByDsProcessInstanceId(dsProcessInstanceId);
            if (existing != null) return existing;

            Dag dag = dagMapper.selectById(dagId);
            if (dag == null) {
                logger.warn("自动创建执行记录失败：DAG 不存在 dagId={}", dagId);
                return null;
            }

            DagExecution ex = new DagExecution();
            ex.setId(IdWorker.getId());
            ex.setDagId(dagId);
            ex.setDsProcessInstanceId(dsProcessInstanceId);
            ex.setTriggerType("CRON");
            ex.setStatus("RUNNING");
            ex.setStartTime(LocalDateTime.now());
            ex.setCreatedBy(0L);
            ex.setCreatedAt(LocalDateTime.now());
            try {
                dagExecutionMapper.insert(ex);
            } catch (DuplicateKeyException e) {
                logger.warn("DAG 已有 RUNNING 执行，无法为 DS 实例 {} 创建记录", dsProcessInstanceId);
                return null;
            }

            List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
            if (!nodes.isEmpty()) {
                List<NodeExecution> nes = new ArrayList<>(nodes.size());
                for (DagNode node : nodes) {
                    NodeExecution ne = new NodeExecution();
                    ne.setId(IdWorker.getId());
                    ne.setExecutionId(ex.getId());
                    ne.setNodeId(node.getNodeId());
                    ne.setNodeName(node.getNodeName());
                    ne.setNodeType(node.getNodeType());
                    ne.setStatus("WAITING");
                    nes.add(ne);
                }
                nodeExecutionMapper.insertBatch(nes);
            }

            logger.info("为 DS 定时实例自动创建执行记录: dagId={}, dsProcessInstanceId={}, executionId={}",
                    dagId, dsProcessInstanceId, ex.getId());
            return ex;
        }
    }

    private record NodeExecutionLookup(NodeExecution nodeExecution) {
    }

    private long currentUserId() {
        try {
            return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String stringOf(Object o) {
        return o == null ? null : o.toString();
    }

    private Long longOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 成功响应。HTTP 恒为 200，成败由 body code 表达（DS HTTP 任务用 STATUS_CODE_DEFAULT 判断）。
     * 统一用 Result 信封后，成功 code 由原来的 0 对齐为项目统一的 200，DS 侧只校验 HTTP 状态码，不受影响。
     */
    private Result<Map<String, Integer>> success(int affectedRows) {
        return Result.ok(Map.of("affectedRows", affectedRows));
    }

    private Result<Map<String, Integer>> error(String msg, int httpStatus) {
        return Result.fail(httpStatus, msg);
    }
}
