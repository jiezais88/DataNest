package com.datanest.worker.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.common.constant.TaskTriggerType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.ConditionNodeConfig;
import com.datanest.task.core.dto.PythonExecuteResult;
import com.datanest.task.core.dto.PythonNodeConfig;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.*;
import com.datanest.task.core.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sprint 4 架构调整：DAG 节点执行服务。
 * 运行在 data-nest-worker，直接操作业务库。
 */
@Service
public class DagNodeExecuteService {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeExecuteService.class);

    /** 条件分支表达式变量占位符：${a.b} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /** 按 DS processInstanceId 加锁，防止同一实例的并发回调重复创建 dag_execution */
    private static final ConcurrentHashMap<Long, Object> EXECUTION_LOCKS = new ConcurrentHashMap<>();

    private static final int SQL_OUTPUT_PREVIEW_MAX_ROWS = 50;

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final DagEdgeMapper dagEdgeMapper;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final MetadataRegistrationService metadataRegistrationService;
    private final DagExecutionSyncService dagExecutionSyncService;
    private final DagParameterResolver dagParameterResolver;
    private final NodeExecutionLogService nodeExecutionLogService;
    private final SqlLineageExtractor sqlLineageExtractor;
    private final PythonExecutor pythonExecutor;
    private final SyncJobTriggerService syncJobTriggerService;
    private final SyncNodeMutexService syncNodeMutexService;

    public DagNodeExecuteService(DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                                 DagMapper dagMapper, DagNodeMapper dagNodeMapper, DagEdgeMapper dagEdgeMapper,
                                 DorisSqlExecutor dorisSqlExecutor,
                                 MetadataRegistrationService metadataRegistrationService,
                                 DagExecutionSyncService dagExecutionSyncService,
                                 DagParameterResolver dagParameterResolver,
                                 NodeExecutionLogService nodeExecutionLogService,
                                 SqlLineageExtractor sqlLineageExtractor,
                                 PythonExecutor pythonExecutor,
                                 SyncJobTriggerService syncJobTriggerService,
                                 SyncNodeMutexService syncNodeMutexService) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.dagEdgeMapper = dagEdgeMapper;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.metadataRegistrationService = metadataRegistrationService;
        this.dagExecutionSyncService = dagExecutionSyncService;
        this.dagParameterResolver = dagParameterResolver;
        this.nodeExecutionLogService = nodeExecutionLogService;
        this.sqlLineageExtractor = sqlLineageExtractor;
        this.pythonExecutor = pythonExecutor;
        this.syncJobTriggerService = syncJobTriggerService;
        this.syncNodeMutexService = syncNodeMutexService;
    }


    /**
     * SQL 节点执行
     */
    public Result<Map<String, Integer>> handleSqlNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        String rawSqlContent = stringOf(body.get("sqlContent"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        Long dagId = longOf(body.get("dagId"));
        if (nodeId == null || rawSqlContent == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 nodeId / sqlContent");
        }
        NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
        if (lookup.nodeExecution == null) {
            logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "node execution not found: nodeId=" + nodeId + ", dsProcessInstanceId=" + dsProcessInstanceId);
        }
        NodeExecution ne = lookup.nodeExecution;
        // Sprint 5：条件分支 gate —— 非命中分支的节点标记 SKIPPED，不真正执行
        if (skipIfConditionGated(ne, dagId)) {
            return Result.ok(Map.of("affectedRows", 0));
        }
        ne.setStatus("RUNNING");
        ne.setStartTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);

        DagExecution execution = dagExecutionMapper.selectById(ne.getExecutionId());
        Map<String, Object> params = dagParameterResolver.resolveParams(
                dagId, execution != null ? parseJsonMap(execution.getResolvedParams()) : null);
        String sqlContent = dagParameterResolver.replacePlaceholders(rawSqlContent, params);

        List<String> logLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        LocalDateTime startTime = LocalDateTime.now();
        try {
            String type = classifySql(sqlContent);
            SqlOutputInfo output = switch (type) {
                case "QUERY" -> executeQuery(sqlContent);
                case "DML" -> executeDml(sqlContent);
                case "DDL" -> executeDdl(sqlContent);
                default -> executeUnknown(sqlContent);
            };

            // 元数据注册
            try {
                if ("DDL".equals(type) || "DML".equals(type)) {
                    Dag dag = dagMapper.selectById(dagId);
                    DagNode node = dagNodeMapper.selectList(
                                    new QueryWrapper<DagNode>()
                                            .eq("dag_id", dagId).eq("node_id", nodeId).last("LIMIT 1"))
                            .stream().findFirst().orElse(null);
                    MetadataRegistrationService.SourceContext ctx = new MetadataRegistrationService.SourceContext(
                            "SQL", dagId, dag == null ? null : dag.getName(),
                            nodeId, node == null ? null : node.getNodeName());
                    List<String> registered = metadataRegistrationService.registerFromSql(sqlContent, currentUserId(), ctx);
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
            ne.setDurationMs(Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis());
            nodeExecutionMapper.updateById(ne);

            logLines.add("[INFO] SQL 执行成功，类型: " + type);
            saveNodeLogs(ne.getExecutionId(), nodeId, logLines);

            recordSqlLineage(sqlContent, dagId, nodeId, ne.getExecutionId());
            finalizeExecutionQuietly(ne.getExecutionId());
            return Result.ok(Map.of("affectedRows", "DML".equals(type) ? output.affectedRows : 0));
        } catch (BusinessException e) {
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            ne.setDurationMs(Duration.between(startTime, LocalDateTime.now()).toMillis());
            nodeExecutionMapper.updateById(ne);
            logLines.add("[ERROR] " + e.getMessage());
            saveNodeLogs(ne.getExecutionId(), nodeId, logLines);
            finalizeExecutionQuietly(ne.getExecutionId());
            throw e;
        } catch (Exception e) {
            logger.error("SQL 节点执行失败: nodeId={}", nodeId, e);
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            ne.setDurationMs(Duration.between(startTime, LocalDateTime.now()).toMillis());
            nodeExecutionMapper.updateById(ne);
            logLines.add("[ERROR] " + e.getMessage());
            saveNodeLogs(ne.getExecutionId(), nodeId, logLines);
            finalizeExecutionQuietly(ne.getExecutionId());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "SQL 节点执行失败: " + e.getMessage(), e);
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

    private String extractTargetTable(String sql) {
        if (sql == null) return null;
        String normalized = sql.replaceAll("--[^\\n]*", " ").replaceAll("/\\*.*?\\*/", " ")
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
                return normalized.substring(m.start(1), m.end(1));
            }
        }
        return null;
    }

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
     * SYNC 节点执行：触发同步任务
     */
    public Result<Map<String, Integer>> handleSyncNode(Map<String, Object> body) {
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

        String lockToken = syncNodeMutexService.tryLock(syncJobId);
        try {
            NodeExecutionLookup lookup = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId);
            if (lookup.nodeExecution == null) {
                logger.warn("回调找不到对应 node_execution: nodeId={}, dsProcessInstanceId={}", nodeId, dsProcessInstanceId);
                syncNodeMutexService.unlock(syncJobId, lockToken);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "node execution not found: nodeId=" + nodeId + ", dsProcessInstanceId=" + dsProcessInstanceId);
            }
            NodeExecution ne = lookup.nodeExecution;
            // Sprint 5：条件分支 gate —— 非命中分支的节点标记 SKIPPED，不真正执行（需释放锁）
            if (skipIfConditionGated(ne, dagId)) {
                syncNodeMutexService.unlock(syncJobId, lockToken);
                return Result.ok(Map.of("affectedRows", 0));
            }
            ne.setStatus("RUNNING");
            ne.setStartTime(LocalDateTime.now());
            ne.setSyncJobId(syncJobId);

            Long historyId = syncJobTriggerService.triggerSyncJob(syncJobId, TaskTriggerType.DAG.getCode(), ne.getExecutionId());
            ne.setSyncJobHistoryId(historyId);
            nodeExecutionMapper.updateById(ne);

            return Result.ok(Map.of("affectedRows", 0));
        } catch (BusinessException e) {
            syncNodeMutexService.unlock(syncJobId, lockToken);
            throw e;
        } catch (Exception e) {
            logger.error("同步节点触发失败: nodeId={}, syncJobId={}", nodeId, syncJobId, e);
            syncNodeMutexService.unlock(syncJobId, lockToken);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步节点触发失败: " + e.getMessage(), e);
        }
        // 正常路径：锁由 DagExecutionSyncService SPI 收尾时释放
    }

    /**
     * PYTHON 节点执行
     */
    public Result<Map<String, Object>> handlePythonNode(Map<String, Object> body) {
        Long dagId = longOf(body.get("dagId"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        String nodeId = stringOf(body.get("nodeId"));
        if (dagId == null || dsProcessInstanceId == null || nodeId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 dagId / executionId / nodeId");
        }

        DagNode node = dagNodeMapper.selectList(
                        new QueryWrapper<DagNode>()
                                .eq("dag_id", dagId).eq("node_id", nodeId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (node == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "节点不存在: " + nodeId);
        }

        PythonNodeConfig config = parsePythonConfig(node.getConfig());
        if (!StringUtils.hasText(config.getPythonScript())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Python 脚本为空: " + nodeId);
        }

        NodeExecution ne = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId).nodeExecution;
        if (ne == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "node_execution 不存在: " + nodeId);
        }
        // Sprint 5：条件分支 gate —— 非命中分支的节点标记 SKIPPED，不真正执行
        if (skipIfConditionGated(ne, dagId)) {
            return Result.ok(Map.of("outputTables", List.of(), "skipped", true));
        }

        ne.setStatus("RUNNING");
        ne.setStartTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);

        DagExecution execution = dagExecutionMapper.selectById(ne.getExecutionId());
        Map<String, Object> params = dagParameterResolver.resolveParams(
                dagId, execution != null ? parseJsonMap(execution.getResolvedParams()) : null);
        String script = dagParameterResolver.replacePlaceholders(config.getPythonScript(), params);

        List<NodeExecutionLogService.LogLine> logLines = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> logCollector = line -> logLines.add(
                new NodeExecutionLogService.LogLine(line.startsWith("[STDERR]") ? "ERROR" : "INFO", line));

        LocalDateTime startTime = LocalDateTime.now();
        try {
            Integer timeoutSeconds = config.getTimeoutMinutes() != null && config.getTimeoutMinutes() > 0
                    ? config.getTimeoutMinutes() * 60 : null;
            PythonExecuteResult result = pythonExecutor.execute(
                    script,
                    new PythonExecutor.PythonContext(params, logCollector),
                    timeoutSeconds,
                    config.getMemoryLimitMb());

            long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            ne.setDurationMs(durationMs);
            ne.setEndTime(LocalDateTime.now());

            if (result.isSuccess()) {
                ne.setStatus("SUCCESS");
                ne.setOutputInfo(JSON.toJSONString(result));
                nodeExecutionMapper.updateById(ne);
                savePythonLogs(ne.getExecutionId(), nodeId, logLines);

                Dag dag = dagMapper.selectById(dagId);
                String dagName = dag != null ? dag.getName() : null;

                List<String> outputTables = result.getOutputTables();
                if (outputTables != null && !outputTables.isEmpty()) {
                    for (String table : outputTables) {
                        try {
                            metadataRegistrationService.registerFromPython(table, dagId, dagName, nodeId, node.getNodeName(), ne.getExecutionId());
                        } catch (Exception e) {
                            logger.warn("Python 输出表元数据注册失败: table={}", table, e);
                        }
                    }
                    recordPythonLineage(dagId, dagName, nodeId, node.getNodeName(), ne.getExecutionId(), outputTables);
                }

                dagExecutionSyncService.finalizeIfAllDone(ne.getExecutionId());
                return Result.ok(Map.of("outputTables", outputTables == null ? List.of() : outputTables,
                        "durationMs", durationMs));
            } else {
                ne.setStatus("FAILED");
                String err = result.getStderr();
                if (!StringUtils.hasText(err)) err = "Python 执行失败";
                ne.setErrorMessage(err);
                nodeExecutionMapper.updateById(ne);
                savePythonLogs(ne.getExecutionId(), nodeId, logLines);
                dagExecutionSyncService.finalizeIfAllDone(ne.getExecutionId());
                throw new BusinessException(ErrorCode.DAG_NODE_EXECUTE_FAILED, err);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Python 节点回调异常: nodeId={}", nodeId, e);
            ne.setStatus("FAILED");
            ne.setErrorMessage(e.getMessage());
            ne.setEndTime(LocalDateTime.now());
            ne.setDurationMs(Duration.between(startTime, LocalDateTime.now()).toMillis());
            nodeExecutionMapper.updateById(ne);
            savePythonLogs(ne.getExecutionId(), nodeId, logLines);
            dagExecutionSyncService.finalizeIfAllDone(ne.getExecutionId());
            throw new BusinessException(ErrorCode.DAG_NODE_EXECUTE_FAILED, "Python 节点执行失败: " + e.getMessage());
        }
    }

    private PythonNodeConfig parsePythonConfig(String configJson) {
        try {
            return JSON.parseObject(configJson, PythonNodeConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Python 节点配置解析失败: " + e.getMessage());
        }
    }

    /**
     * CONDITION 节点执行（Sprint 5）。
     * worker 读取分支配置，用上游节点输出构建 SpEL 求值上下文，
     * 按顺序求值命中分支，把结果（branchIndex / nextNodeId）写入 node_execution.output_info，
     * 供后续非命中分支下游节点的 gate 判断跳过。
     */
    public Result<Map<String, Object>> handleConditionNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        Long dagId = longOf(body.get("dagId"));
        Long dsProcessInstanceId = longOf(body.get("executionId"));
        if (nodeId == null || dagId == null || dsProcessInstanceId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 nodeId / dagId / executionId");
        }

        NodeExecution ne = resolveNodeExecution(nodeId, dagId, dsProcessInstanceId).nodeExecution;
        if (ne == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "node_execution 不存在: " + nodeId);
        }
        // 条件节点自身也可能处于非命中分支（条件嵌套），先过 gate
        if (skipIfConditionGated(ne, dagId)) {
            return Result.ok(Map.of("branchIndex", -1, "skipped", true));
        }

        ne.setStatus("RUNNING");
        ne.setStartTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);

        DagNode node = dagNodeMapper.selectList(
                        new QueryWrapper<DagNode>()
                                .eq("dag_id", dagId).eq("node_id", nodeId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (node == null || !StringUtils.hasText(node.getConfig())) {
            throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID, "条件节点配置缺失: " + nodeId);
        }
        ConditionNodeConfig config = parseConditionConfig(node.getConfig());
        if (config.getBranches() == null || config.getBranches().size() < 2) {
            throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID, "条件分支至少 2 个: " + nodeId);
        }

        LocalDateTime startTime = LocalDateTime.now();
        Map<String, Object> vars = buildConditionContext(ne.getExecutionId(), dagId, nodeId);
        int branchIndex = evaluateBranches(config.getBranches(), vars);
        ConditionNodeConfig.ConditionBranch selected = config.getBranches().get(branchIndex);

        ne.setOutputInfo(JSON.toJSONString(Map.of(
                "branchIndex", branchIndex,
                "nextNodeId", selected.getNextNodeId(),
                "branchName", selected.getBranchName())));
        ne.setStatus("SUCCESS");
        ne.setEndTime(LocalDateTime.now());
        ne.setDurationMs(Duration.between(startTime, LocalDateTime.now()).toMillis());
        nodeExecutionMapper.updateById(ne);

        logger.info("条件分支求值: executionId={}, nodeId={}, branchIndex={}, nextNodeId={}",
                ne.getExecutionId(), nodeId, branchIndex, selected.getNextNodeId());
        finalizeExecutionQuietly(ne.getExecutionId());
        return Result.ok(Map.of("branchIndex", branchIndex, "nextNodeId", selected.getNextNodeId()));
    }

    private ConditionNodeConfig parseConditionConfig(String configJson) {
        try {
            return JSON.parseObject(configJson, ConditionNodeConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID,
                    "条件节点配置解析失败: " + e.getMessage());
        }
    }

    /**
     * 条件分支 gate：命中非命中分支（或上游全被跳过）的节点标记 SKIPPED 并收尾。
     * @return true 表示本节点应被跳过（已标记 SKIPPED）
     */
    private boolean skipIfConditionGated(NodeExecution ne, Long dagId) {
        if (ne == null) {
            return false;
        }
        if (!shouldSkipNode(ne.getExecutionId(), ne.getNodeId(), dagId)) {
            return false;
        }
        ne.setStatus("SKIPPED");
        ne.setEndTime(LocalDateTime.now());
        if (ne.getStartTime() != null) {
            ne.setDurationMs(Duration.between(ne.getStartTime(), ne.getEndTime()).toMillis());
        }
        nodeExecutionMapper.updateById(ne);
        finalizeExecutionQuietly(ne.getExecutionId());
        logger.info("条件分支节点被跳过: executionId={}, nodeId={}", ne.getExecutionId(), ne.getNodeId());
        return true;
    }

    /**
     * 判断节点是否属于「非命中分支」：全部入边都不激活时跳过。
     * - 条件节点入边：仅当条件节点选中的 nextNodeId 等于本节点时激活
     * - 普通节点入边：前驱未被 SKIPPED 视为激活
     * DAG 中没有 CONDITION 节点时走快速路径，不额外查询。
     */
    private boolean shouldSkipNode(Long executionId, String nodeId, Long dagId) {
        try {
            if (executionId == null || dagId == null) {
                return false;
            }
            List<DagNode> nodes = dagNodeMapper.selectByDagId(dagId);
            boolean hasCondition = nodes.stream()
                    .anyMatch(n -> "CONDITION".equalsIgnoreCase(n.getNodeType()));
            if (!hasCondition) {
                return false;
            }
            Map<String, String> nodeTypeMap = nodes.stream()
                    .collect(Collectors.toMap(DagNode::getNodeId, DagNode::getNodeType, (a, b) -> a));
            List<DagEdge> incoming = dagEdgeMapper.selectByDagId(dagId).stream()
                    .filter(e -> nodeId.equals(e.getTargetNodeId()))
                    .toList();
            if (incoming.isEmpty()) {
                return false;
            }
            Map<String, NodeExecution> neByNodeId = nodeExecutionMapper.selectByExecutionId(executionId).stream()
                    .collect(Collectors.toMap(NodeExecution::getNodeId, n -> n, (a, b) -> a));

            boolean anyActive = false;
            for (DagEdge edge : incoming) {
                String srcNodeId = edge.getSourceNodeId();
                NodeExecution srcNe = neByNodeId.get(srcNodeId);
                if ("CONDITION".equalsIgnoreCase(nodeTypeMap.get(srcNodeId))) {
                    // 条件前驱：命中本分支才激活
                    if (srcNe != null && nodeId.equals(extractSelectedNextNodeId(srcNe.getOutputInfo()))) {
                        anyActive = true;
                    }
                } else {
                    // 普通前驱：未 SKIPPED 视为激活（DS 按依赖序执行，前驱已终态）
                    if (srcNe == null || !"SKIPPED".equalsIgnoreCase(srcNe.getStatus())) {
                        anyActive = true;
                    }
                }
            }
            return !anyActive;
        } catch (Exception e) {
            logger.warn("条件分支 gate 判断失败，按不跳过处理: executionId={}, nodeId={}", executionId, nodeId, e);
            return false;
        }
    }

    private String extractSelectedNextNodeId(String outputInfo) {
        if (!StringUtils.hasText(outputInfo)) {
            return null;
        }
        try {
            return JSON.parseObject(outputInfo).getString("nextNodeId");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建条件求值上下文：
     * - upstream：直接前驱节点输出合并后的 map（并补充 row_count / status 归一化字段）
     * - DAG 参数平铺（如 biz_date）
     * - 系统变量：dag_id、current_time
     */
    private Map<String, Object> buildConditionContext(Long executionId, Long dagId, String nodeId) {
        Map<String, Object> vars = new HashMap<>();
        Map<String, Object> upstream = new HashMap<>();
        List<String> predNodeIds = dagEdgeMapper.selectByDagId(dagId).stream()
                .filter(e -> nodeId.equals(e.getTargetNodeId()))
                .map(DagEdge::getSourceNodeId)
                .toList();
        if (!predNodeIds.isEmpty()) {
            nodeExecutionMapper.selectByExecutionId(executionId).stream()
                    .filter(n -> predNodeIds.contains(n.getNodeId()))
                    .forEach(pred -> {
                        Map<String, Object> out = parseJsonMap(pred.getOutputInfo());
                        out.put("status", pred.getStatus());
                        normalizeRowCount(out);
                        upstream.putAll(out);
                    });
        }
        vars.put("upstream", upstream);

        DagExecution execution = dagExecutionMapper.selectById(executionId);
        Map<String, Object> dagParams = dagParameterResolver.resolveParams(
                dagId, execution != null ? parseJsonMap(execution.getResolvedParams()) : null);
        dagParams.forEach(vars::put);

        vars.put("dag_id", dagId);
        vars.put("current_time", LocalDateTime.now().toString());
        return vars;
    }

    /**
     * 为上游输出补充 row_count 归一化字段（SQL DML 用 affectedRows，QUERY 用 returnedRows）。
     */
    private void normalizeRowCount(Map<String, Object> out) {
        if (out.containsKey("row_count")) {
            return;
        }
        Object rowCount = out.get("affectedRows");
        if (!(rowCount instanceof Number)) {
            rowCount = out.get("returnedRows");
        }
        if (rowCount instanceof Number) {
            out.put("row_count", ((Number) rowCount).longValue());
        }
    }

    /**
     * 按顺序求值分支表达式（SpEL 只读数据绑定，禁止方法调用，防注入 R6），
     * 第一个匹配的返回分支索引；均不匹配返回默认分支（0）。
     * 约定 branches[0] 为默认兜底分支（表达式恒为 "true"，前端锁定），
     * 因此从分支 1 开始求值真实条件，避免默认分支恒先命中导致其余分支永远不执行。
     */
    private int evaluateBranches(List<ConditionNodeConfig.ConditionBranch> branches, Map<String, Object> vars) {
        SpelExpressionParser parser = new SpelExpressionParser();
        EvaluationContext ctx = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        vars.forEach(ctx::setVariable);
        for (int i = 1; i < branches.size(); i++) {
            String raw = branches.get(i).getExpression();
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            try {
                // ${upstream.row_count} → #upstream['row_count']（SpEL 索引语法）。
                // 默认 SimpleEvaluationContext 不含 MapAccessor，`#upstream.row_count` 属性语法必然抛
                // "Property ... cannot be found"，导致真实条件分支永不命中；索引语法可用。
                String expr = VAR_PATTERN.matcher(raw).replaceAll(mr -> toSpelExpr(mr.group(1)));
                Boolean matched = parser.parseExpression(expr).getValue(ctx, Boolean.class);
                if (Boolean.TRUE.equals(matched)) {
                    return i;
                }
            } catch (Exception e) {
                logger.warn("条件分支求值失败，跳过该分支: expr={}, err={}", raw, e.getMessage());
            }
        }
        return 0;
    }

    /** ${a.b.c} → #a['b']['c']；${a} → #a */
    private String toSpelExpr(String rawVar) {
        String[] parts = rawVar.trim().split("\\.");
        StringBuilder sb = new StringBuilder("#").append(parts[0].trim());
        for (int i = 1; i < parts.length; i++) {
            sb.append("['").append(parts[i].trim()).append("']");
        }
        return sb.toString();
    }


    /**
     * 通过 DS processInstanceId 反查 dag_execution，再按 nodeId 查 node_execution。
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
                        new QueryWrapper<NodeExecution>()
                                .eq("execution_id", dagExecution.getId()).eq("node_id", nodeId)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        return new NodeExecutionLookup(ne);
    }

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

    private void finalizeExecutionQuietly(Long executionId) {
        try {
            dagExecutionSyncService.finalizeIfAllDone(executionId);
        } catch (Exception e) {
            logger.warn("节点回调后立即收尾 dag_execution 失败（由定时同步兜底）: executionId={}", executionId, e);
        }
    }

    private void saveNodeLogs(Long executionId, String nodeId, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        try {
            List<NodeExecutionLogService.LogLine> logLines = lines.stream()
                    .map(line -> new NodeExecutionLogService.LogLine(
                            line.startsWith("[ERROR]") ? "ERROR" : "INFO", line))
                    .toList();
            nodeExecutionLogService.saveLogs(executionId, nodeId, logLines);
        } catch (Exception e) {
            logger.warn("保存 SQL 节点日志失败", e);
        }
    }

    private void savePythonLogs(Long executionId, String nodeId, List<NodeExecutionLogService.LogLine> logLines) {
        if (logLines == null || logLines.isEmpty()) {
            return;
        }
        try {
            nodeExecutionLogService.saveLogs(executionId, nodeId, logLines);
        } catch (Exception e) {
            logger.warn("保存 Python 节点日志失败", e);
        }
    }

    private void recordSqlLineage(String sqlContent, Long dagId, String nodeId, Long executionId) {
        try {
            Dag dag = dagMapper.selectById(dagId);
            DagNode node = dagNodeMapper.selectList(
                            new QueryWrapper<DagNode>()
                                    .eq("dag_id", dagId).eq("node_id", nodeId).last("LIMIT 1"))
                    .stream().findFirst().orElse(null);
            sqlLineageExtractor.extract(sqlContent, dagId, dag == null ? null : dag.getName(),
                    nodeId, node == null ? null : node.getNodeName(), executionId);
        } catch (Exception e) {
            logger.warn("记录 SQL 血缘失败", e);
        }
    }

    private void recordPythonLineage(Long dagId, String dagName, String nodeId, String nodeName,
                                     Long executionId, List<String> outputTables) {
        try {
            sqlLineageExtractor.recordPythonLineage(outputTables, dagId, dagName, nodeId, nodeName, executionId);
        } catch (Exception e) {
            logger.warn("记录 Python 血缘失败", e);
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return JSON.parseObject(json, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private long currentUserId() {
        // worker 无登录态，回调场景统一用 0
        return 0L;
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

    private record NodeExecutionLookup(NodeExecution nodeExecution) {
    }
}
