package com.datanest.engineering.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.service.DagEdgeSnapshot;
import com.datanest.engineering.service.SyncJobService;
import com.datanest.engineering.service.SyncNodeMutexService;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.*;
import com.datanest.task.core.service.DagExecutionSyncService;
import com.datanest.task.core.service.DorisSqlExecutor;
import com.datanest.task.core.service.MetadataRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final DagEdgeMapper dagEdgeMapper;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final MetadataRegistrationService metadataRegistrationService;
    private final SyncJobService syncJobService;
    private final SyncNodeMutexService syncNodeMutexService;
    private final DagExecutionSyncService dagExecutionSyncService;

    public DagNodeCallbackController(DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                                     DagMapper dagMapper, DagNodeMapper dagNodeMapper,
                                     DagEdgeMapper dagEdgeMapper,
                                     DorisSqlExecutor dorisSqlExecutor,
                                     MetadataRegistrationService metadataRegistrationService,
                                     SyncJobService syncJobService,
                                     SyncNodeMutexService syncNodeMutexService,
                                     DagExecutionSyncService dagExecutionSyncService) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.metadataRegistrationService = metadataRegistrationService;
        this.syncJobService = syncJobService;
        this.syncNodeMutexService = syncNodeMutexService;
        this.dagExecutionSyncService = dagExecutionSyncService;
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
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未知节点类型");
    }

    /**
     * Sprint 3 P0-1：executionId 是 DS processInstanceId（来自 ${system.workflow.instance.id} 变量）
     * 用它反查 DataNest 的 dag_execution.id，再去查 node_execution
     */
    /** SQL 节点输出信息里结果集预览的最大行数 */
    private static final int SQL_OUTPUT_PREVIEW_MAX_ROWS = 50;

    private Result<Map<String, Integer>> handleSqlNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        String sqlContent = stringOf(body.get("sqlContent"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        Long dagId = longOf(body.get("dagId"));
        if (nodeId == null || sqlContent == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 nodeId / sqlContent");
        }
        NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
        if (lookup.nodeExecution == null) {
            logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "node execution not found: nodeId=" + nodeId + ", dsProcessInstanceId=" + dsProcessInstanceId);
        }
        NodeExecution ne = lookup.nodeExecution;
        ne.setStatus("RUNNING");
        ne.setStartTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);

        try {
            String type = classifySql(sqlContent);
            SqlOutputInfo output = switch (type) {
                case "QUERY" -> executeQuery(sqlContent);
                case "DML" -> executeDml(sqlContent);
                case "DDL" -> executeDdl(sqlContent);
                default -> executeUnknown(sqlContent);
            };

            // 元数据注册：仅对 DDL/DML 尽力而为，失败不影响节点成功
            try {
                if ("DDL".equals(type) || "DML".equals(type)) {
                    List<String> registered = metadataRegistrationService.registerFromSql(sqlContent, currentUserId());
                    if (!registered.isEmpty()) {
                        output.registeredTables = registered;
                    }
                }
            } catch (Exception e) {
                logger.warn("元数据注册失败（不影响 SQL 执行结果）: {}", e.getMessage());
            }

            ne.setOutputInfo(JSON.toJSONString(output));
            ne.setStatus("SUCCESS");
            ne.setEndTime(LocalDateTime.now());
            ne.setDurationMs(java.time.Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis());
            nodeExecutionMapper.updateById(ne);
            // 耗时准实时：节点写完终态后立即推断 dag_execution 终态，不等下一轮定时同步
            finalizeExecutionQuietly(ne.getExecutionId());
            return success("DML".equals(type) ? output.affectedRows : 0);
        } catch (BusinessException e) {
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            nodeExecutionMapper.updateById(ne);
            finalizeExecutionQuietly(ne.getExecutionId());
            throw e;
        } catch (Exception e) {
            logger.error("SQL 节点执行失败: nodeId={}", nodeId, e);
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            nodeExecutionMapper.updateById(ne);
            finalizeExecutionQuietly(ne.getExecutionId());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "SQL 节点执行失败: " + e.getMessage(), e);
        }
    }

    private SqlOutputInfo executeQuery(String sql) {
        DorisSqlExecutor.QueryResult qr = dorisSqlExecutor.query(sql);
        SqlOutputInfo info = new SqlOutputInfo();
        info.sqlType = "QUERY";
        info.returnedRows = qr.rows().size();
        info.columns = qr.columns();
        info.previewRows = qr.rows().stream()
                .map(row -> qr.columns().stream().map(row::get).toList())
                .limit(SQL_OUTPUT_PREVIEW_MAX_ROWS)
                .toList();
        info.truncated = qr.rows().size() > SQL_OUTPUT_PREVIEW_MAX_ROWS || qr.truncated();
        info.targetTable = extractTargetTable(sql);
        return info;
    }

    private SqlOutputInfo executeDml(String sql) {
        int affected = dorisSqlExecutor.execute(sql);
        SqlOutputInfo info = new SqlOutputInfo();
        info.sqlType = "DML";
        info.affectedRows = affected;
        info.targetTable = extractTargetTable(sql);
        return info;
    }

    private SqlOutputInfo executeDdl(String sql) {
        int affected = dorisSqlExecutor.execute(sql);
        SqlOutputInfo info = new SqlOutputInfo();
        info.sqlType = "DDL";
        info.affectedRows = affected;
        info.targetTable = extractTargetTable(sql);
        return info;
    }

    private SqlOutputInfo executeUnknown(String sql) {
        int affected = dorisSqlExecutor.execute(sql);
        SqlOutputInfo info = new SqlOutputInfo();
        info.sqlType = "UNKNOWN";
        info.affectedRows = affected;
        info.targetTable = extractTargetTable(sql);
        return info;
    }

    private String classifySql(String sql) {
        String trimmed = sql.trim();
        int firstSpace = trimmed.indexOf(' ');
        String first = firstSpace > 0 ? trimmed.substring(0, firstSpace) : trimmed;
        String upper = first.toUpperCase();
        if (upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("SHOW")
                || upper.startsWith("DESC") || upper.startsWith("EXPLAIN") || upper.startsWith("VALUES")) {
            return "QUERY";
        }
        if (upper.startsWith("CREATE") || upper.startsWith("DROP") || upper.startsWith("ALTER")
                || upper.startsWith("TRUNCATE") || upper.startsWith("RENAME") || upper.startsWith("COMMENT")) {
            return "DDL";
        }
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE")
                || upper.startsWith("MERGE")) {
            return "DML";
        }
        return "UNKNOWN";
    }

    /**
     * 从 SQL 中粗略提取目标表名（CREATE TABLE / INSERT INTO / UPDATE / DELETE FROM / CTAS）。
     * 仅用于展示，不需要 100% 精确；提取失败返回 null。
     */
    private String extractTargetTable(String sql) {
        if (sql == null) return null;
        String normalized = sql.replaceAll("--[^\n]*", " ").replaceAll("/\\*.*?\\*/", " ")
                .replaceAll("\\s+", " ").trim();
        String upper = normalized.toUpperCase();
        java.util.regex.Pattern[] patterns = new java.util.regex.Pattern[]{
                java.util.regex.Pattern.compile("\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_.]+)"),
                java.util.regex.Pattern.compile("\\bINSERT\\s+(?:INTO\\s+)?([A-Za-z0-9_.]+)"),
                java.util.regex.Pattern.compile("\\bUPDATE\\s+([A-Za-z0-9_.]+)"),
                java.util.regex.Pattern.compile("\\bDELETE\\s+FROM\\s+([A-Za-z0-9_.]+)"),
                java.util.regex.Pattern.compile("\\bFROM\\s+([A-Za-z0-9_.]+)")
        };
        for (java.util.regex.Pattern p : patterns) {
            java.util.regex.Matcher m = p.matcher(upper);
            if (m.find()) {
                String raw = normalized.substring(m.start(1), m.end(1));
                return raw;
            }
        }
        return null;
    }

    /**
     * SQL 节点输出信息的结构化摘要（写入 node_execution.output_info）。
     */
    private static class SqlOutputInfo {
        public String sqlType;
        public Integer affectedRows;
        public Integer returnedRows;
        public List<String> columns;
        public List<List<Object>> previewRows;
        public Boolean truncated;
        public String targetTable;
        public List<String> registeredTables;
    }

    /**
     * 节点写完终态后立即推断 dag_execution 终态（耗时准实时）。
     * 失败只记日志不影响回调响应，由 DagExecutionSyncHandler 定时同步兜底。
     */
    private void finalizeExecutionQuietly(Long executionId) {
        try {
            dagExecutionSyncService.finalizeIfAllDone(executionId);
        } catch (Exception e) {
            logger.warn("节点回调后立即收尾 dag_execution 失败（由定时同步兜底）: executionId={}", executionId, e);
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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 nodeId / syncJob");
        }
        Long syncJobId = longOf(((Map<?, ?>) syncJobObj).get("id"));
        if (syncJobId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "syncJob.id 缺失");
        }

        // P0-2：互斥锁（拿不到抛 DAG_ALREADY_RUNNING，由本地 @ExceptionHandler 转为 HTTP 503，DS 会重试）
        String lockToken = syncNodeMutexService.tryLock(syncJobId);
        try {
            NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
            if (lookup.nodeExecution == null) {
                logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
                // 回调找不到记录时立即释放锁，避免阻塞后续触发
                syncNodeMutexService.unlock(syncJobId, lockToken);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "node execution not found: nodeId=" + nodeId + ", dsProcessInstanceId=" + dsProcessInstanceId);
            }
            NodeExecution ne = lookup.nodeExecution;
            ne.setStatus("RUNNING");
            ne.setStartTime(LocalDateTime.now());
            // P1-2：存 sync_job_id 给 DagExecutionSyncService 收尾
            ne.setSyncJobId(syncJobId);

            // 触发 XXL-JOB（异步；本方法返回时 sync 不一定跑完）
            Long historyId = syncJobService.execute(syncJobId);
            // 立即把 history_id 写回节点执行记录，「查看日志」不用等 DagExecutionSyncService 收尾就能拉到
            ne.setSyncJobHistoryId(historyId);
            nodeExecutionMapper.updateById(ne);

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
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "同步节点触发失败: " + e.getMessage(), e);
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
            // 边快照：历史视图（run-view）用快照渲染边，避免后续删节点导致历史实例连线丢失
            ex.setEdgeSnapshot(DagEdgeSnapshot.capture(dagEdgeMapper, dagId));
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

    /**
     * 本地异常处理：DS HTTP 任务只认 HTTP 状态码，callback 失败时必须返回非 2xx，
     * 否则 DS 会把本次任务标记为 SUCCESS，导致 DataNest 侧没有真正执行却显示成功、也看不到日志。
     * 这里覆盖全局的 @RestControllerAdvice，让 callback 专用错误码映射到合适的 HTTP 状态。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusinessException(BusinessException e) {
        HttpStatus status = (e.getErrorCode() == ErrorCode.DAG_ALREADY_RUNNING)
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(Result.fail(e.getErrorCode().getCode(), e.getMessage(), e.getData()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleException(Exception e) {
        logger.error("DAG 节点回调未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), e.getMessage()));
    }
}
