package com.datanest.engineering.controller;

import com.alibaba.fastjson2.JSON;
import com.datanest.common.exception.BusinessException;
import com.datanest.engineering.service.SyncJobService;
import com.datanest.engineering.service.SyncNodeMutexService;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.datanest.task.core.service.DorisSqlExecutor;
import com.datanest.task.core.service.MetadataRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final MetadataRegistrationService metadataRegistrationService;
    private final SyncJobService syncJobService;
    private final SyncNodeMutexService syncNodeMutexService;

    public DagNodeCallbackController(DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                                     DorisSqlExecutor dorisSqlExecutor,
                                     MetadataRegistrationService metadataRegistrationService,
                                     SyncJobService syncJobService,
                                     SyncNodeMutexService syncNodeMutexService) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.metadataRegistrationService = metadataRegistrationService;
        this.syncJobService = syncJobService;
        this.syncNodeMutexService = syncNodeMutexService;
    }

    @PostMapping("/sql/callback")
    public Map<String, Object> sqlCallback(@RequestBody Map<String, Object> body) {
        return handleSqlNode(body);
    }

    @PostMapping("/sync/callback")
    public Map<String, Object> syncCallback(@RequestBody Map<String, Object> body) {
        return handleSyncNode(body);
    }

    @PostMapping("/unknown/callback")
    public Map<String, Object> unknownCallback(@RequestBody Map<String, Object> body) {
        return error("未知节点类型", 400);
    }

    /**
     * Sprint 3 P0-1：executionId 是 DS processInstanceId（来自 ${processInstanceId} 变量）
     * 用它反查 DataNest 的 dag_execution.id，再去查 node_execution
     */
    private Map<String, Object> handleSqlNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        String sqlContent = stringOf(body.get("sqlContent"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        if (nodeId == null || sqlContent == null) {
            return error("缺少 nodeId / sqlContent", 400);
        }
        NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dsProcessInstanceId);
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
     * Sprint 3 P0-2 + P1-2：
     * - P0-2：加 SyncNodeMutexService 互斥，同一 syncJobId 同一时刻只能一个执行实例
     * - P1-2：标 RUNNING（不标 SUCCESS），存 sync_job_id，由 DagExecutionSyncService 收尾
     */
    private Map<String, Object> handleSyncNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        Object syncJobObj = body.get("syncJob");
        if (nodeId == null || syncJobObj == null) {
            return error("缺少 nodeId / syncJob", 400);
        }
        Long syncJobId = longOf(((Map<?, ?>) syncJobObj).get("id"));
        if (syncJobId == null) {
            return error("syncJob.id 缺失", 400);
        }

        // P0-2：互斥锁（拿不到抛 DAG_ALREADY_RUNNING，DS 那边按 HTTP 5xx 处理会重试）
        String token = syncNodeMutexService.tryLock(syncJobId);
        try {
            NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dsProcessInstanceId);
            if (lookup.nodeExecution == null) {
                logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
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
            throw e;
        } catch (Exception e) {
            logger.error("同步节点触发失败: nodeId={}, syncJobId={}", nodeId, syncJobId, e);
            return error(e.getMessage(), 500);
        } finally {
            // P0-2：释放锁（不管成功失败）
            syncNodeMutexService.unlock(syncJobId, token);
        }
    }

    /**
     * Sprint 3 P0-1：通过 DS processInstanceId 反查 dag_execution，再按 nodeId 查 node_execution
     */
    private NodeExecutionLookup resolveNodeExecution(String nodeId, Long dsProcessInstanceId) {
        if (dsProcessInstanceId == null) {
            return new NodeExecutionLookup(null);
        }
        DagExecution dagExecution = dagExecutionMapper.selectByDsProcessInstanceId(dsProcessInstanceId);
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

    private Map<String, Object> success(int affectedRows) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", 0);
        r.put("msg", "success");
        r.put("data", Map.of("affectedRows", affectedRows));
        return r;
    }

    private Map<String, Object> error(String msg, int httpStatus) {
        Map<String, Object> r = new HashMap<>();
        r.put("code", httpStatus);
        r.put("msg", msg);
        return r;
    }
}
