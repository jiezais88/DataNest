package com.datanest.worker.service;

import com.datanest.common.constant.TaskTriggerType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.DagEdgeInfo;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.DagInfo;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.engineering.api.dto.EnsureDagExecutionRequest;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import com.datanest.engineering.api.dto.NodeExecutionMarkRequest;
import com.datanest.engineering.api.dto.NodeLogAppendRequest;
import com.datanest.engineering.api.dto.SyncJobTriggerRequest;
import com.datanest.task.core.dto.ConditionNodeConfig;
import com.datanest.task.core.dto.PythonExecuteResult;
import com.datanest.task.core.dto.PythonNodeConfig;
import com.datanest.common.json.JsonUtils;
import com.datanest.task.core.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sprint 4 架构调整：DAG 节点执行服务。
 * 运行在 data-nest-worker。
 * 微服务化 3.3：dag/dag_node/dag_edge/dag_execution/node_execution/node_execution_log
 * 读写全部经 Feign 调 app-engineering（EngineeringDagApi / EngineeringDagExecutionApi），
 * 不再直写业务库。
 */
@Service
public class DagNodeExecuteService {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeExecuteService.class);

    /** 条件分支表达式变量占位符：${a.b} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static final int SQL_OUTPUT_PREVIEW_MAX_ROWS = 50;

    private final EngineeringDagApi dagApi;
    private final EngineeringDagExecutionApi dagExecutionApi;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final MetadataRegistrationService metadataRegistrationService;
    private final DagExecutionSyncService dagExecutionSyncService;
    private final DagParameterResolver dagParameterResolver;
    private final SqlLineageExtractor sqlLineageExtractor;
    private final PythonExecutor pythonExecutor;
    private final EngineeringSyncJobApi syncJobApi;
    private final SyncNodeMutexService syncNodeMutexService;

    public DagNodeExecuteService(EngineeringDagApi dagApi,
                                 EngineeringDagExecutionApi dagExecutionApi,
                                 DorisSqlExecutor dorisSqlExecutor,
                                 MetadataRegistrationService metadataRegistrationService,
                                 DagExecutionSyncService dagExecutionSyncService,
                                 DagParameterResolver dagParameterResolver,
                                 SqlLineageExtractor sqlLineageExtractor,
                                 PythonExecutor pythonExecutor,
                                 EngineeringSyncJobApi syncJobApi,
                                 SyncNodeMutexService syncNodeMutexService) {
        this.dagApi = dagApi;
        this.dagExecutionApi = dagExecutionApi;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.metadataRegistrationService = metadataRegistrationService;
        this.dagExecutionSyncService = dagExecutionSyncService;
        this.dagParameterResolver = dagParameterResolver;
        this.sqlLineageExtractor = sqlLineageExtractor;
        this.pythonExecutor = pythonExecutor;
        this.syncJobApi = syncJobApi;
        this.syncNodeMutexService = syncNodeMutexService;
    }


    /**
     * SQL 节点执行
     */
    public Result<Map<String, Integer>> handleSqlNode(Map<String, Object> body) {
        String nodeId = stringOf(body.get("nodeId"));
        String rawSqlContent = stringOf(body.get("sqlContent"));
        Long dagId = longOf(body.get("dagId"));
        // PowerJob 流程：handler 直传 dagExecutionId（initParams 或按 wfInstanceId 补齐）
        Long dagExecutionId = longOf(body.get("dagExecutionId"));
        if (nodeId == null || rawSqlContent == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 nodeId / sqlContent");
        }
        NodeExecutionLookup lookup = resolveLookup(nodeId, dagExecutionId);
        if (lookup.nodeExecution == null) {
            logger.warn("找不到对应 node_execution: nodeId={}, dagExecutionId={}", nodeId, dagExecutionId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "node execution not found: nodeId=" + nodeId + ", dagExecutionId=" + dagExecutionId);
        }
        NodeExecutionInfo ne = lookup.nodeExecution;
        // Sprint 5：条件分支 gate —— 非命中分支的节点标记 SKIPPED，不真正执行
        if (skipIfConditionGated(ne, dagId)) {
            return Result.ok(Map.of("affectedRows", 0));
        }
        markNode(ne, markRequest("RUNNING", r -> r.setStartTime(LocalDateTime.now())));

        DagExecutionInfo execution = getExecution(ne.getExecutionId());
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
                    DagInfo dag = getDag(dagId);
                    DagNodeInfo node = getDagNode(dagId, nodeId);
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

            LocalDateTime endTime = LocalDateTime.now();
            markNode(ne, markRequest("SUCCESS", r -> {
                r.setOutputInfo(JsonUtils.toJSONString(output));
                r.setEndTime(endTime);
                r.setDurationMs(Duration.between(ne.getStartTime(), endTime).toMillis());
            }));

            logLines.add("[INFO] SQL 执行成功，类型: " + type);
            saveNodeLogs(ne.getId(), ne.getExecutionId(), nodeId, logLines);

            recordSqlLineage(sqlContent, dagId, nodeId, ne.getExecutionId());
            finalizeExecutionQuietly(ne.getExecutionId());
            return Result.ok(Map.of("affectedRows", "DML".equals(type) ? output.affectedRows : 0));
        } catch (BusinessException e) {
            markNode(ne, failedMark(startTime, e.getMessage()));
            logLines.add("[ERROR] " + e.getMessage());
            saveNodeLogs(ne.getId(), ne.getExecutionId(), nodeId, logLines);
            finalizeExecutionQuietly(ne.getExecutionId());
            throw e;
        } catch (Exception e) {
            logger.error("SQL 节点执行失败: nodeId={}", nodeId, e);
            markNode(ne, failedMark(startTime, e.getMessage()));
            logLines.add("[ERROR] " + e.getMessage());
            saveNodeLogs(ne.getId(), ne.getExecutionId(), nodeId, logLines);
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
        return SqlStatementSplitter.classify(sql);
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
            // PowerJob 流程：handler 直传 dagExecutionId
            Long dagExecutionId = longOf(body.get("dagExecutionId"));
            NodeExecutionLookup lookup = resolveLookup(nodeId, dagExecutionId);
            if (lookup.nodeExecution == null) {
                logger.warn("找不到对应 node_execution: nodeId={}, dagExecutionId={}", nodeId, dagExecutionId);
                syncNodeMutexService.unlock(syncJobId, lockToken);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "node execution not found: nodeId=" + nodeId + ", dagExecutionId=" + dagExecutionId);
            }
            NodeExecutionInfo ne = lookup.nodeExecution;
            // Sprint 5：条件分支 gate —— 非命中分支的节点标记 SKIPPED，不真正执行（需释放锁）
            if (skipIfConditionGated(ne, dagId)) {
                syncNodeMutexService.unlock(syncJobId, lockToken);
                return Result.ok(Map.of("affectedRows", 0));
            }
            LocalDateTime startTime = LocalDateTime.now();

            // 微服务化 3.2：触发逻辑下沉 engineering（按需注册 XXL + mark-running + 建历史），
            // 远程失败 fail-fast（抛出后由下方 catch 释放锁并标节点失败）
            SyncJobTriggerRequest triggerRequest = new SyncJobTriggerRequest();
            triggerRequest.setTriggerType(TaskTriggerType.DAG.getCode());
            triggerRequest.setDagExecutionId(ne.getExecutionId());
            Result<Long> triggerResult = syncJobApi.trigger(syncJobId, triggerRequest);
            if (triggerResult == null || triggerResult.data() == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "同步节点触发失败（engineering 不可达）: syncJobId=" + syncJobId);
            }
            Long historyId = triggerResult.data();
            markNode(ne, markRequest("RUNNING", r -> {
                r.setStartTime(startTime);
                r.setSyncJobId(syncJobId);
                r.setSyncJobHistoryId(historyId);
            }));

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
        String nodeId = stringOf(body.get("nodeId"));
        // PowerJob 流程：dagExecutionId 直传（initParams / wfInstanceId 补齐）
        Long dagExecutionId = longOf(body.get("dagExecutionId"));
        if (dagId == null || nodeId == null || dagExecutionId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 dagId / nodeId / dagExecutionId");
        }

        DagNodeInfo node = getDagNode(dagId, nodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "节点不存在: " + nodeId);
        }

        PythonNodeConfig config = parsePythonConfig(node.getConfig());
        if (!StringUtils.hasText(config.getPythonScript())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Python 脚本为空: " + nodeId);
        }

        NodeExecutionInfo ne = resolveLookup(nodeId, dagExecutionId).nodeExecution;
        if (ne == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "node_execution 不存在: " + nodeId);
        }
        // Sprint 5：条件分支 gate —— 非命中分支的节点标记 SKIPPED，不真正执行
        if (skipIfConditionGated(ne, dagId)) {
            return Result.ok(Map.of("outputTables", List.of(), "skipped", true));
        }

        markNode(ne, markRequest("RUNNING", r -> r.setStartTime(LocalDateTime.now())));

        DagExecutionInfo execution = getExecution(ne.getExecutionId());
        Map<String, Object> params = dagParameterResolver.resolveParams(
                dagId, execution != null ? parseJsonMap(execution.getResolvedParams()) : null);
        String script = dagParameterResolver.replacePlaceholders(config.getPythonScript(), params);

        List<NodeLogAppendRequest.Entry> logLines = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> logCollector = line -> logLines.add(logEntry(
                line.startsWith("[STDERR]") ? "ERROR" : "INFO", line));

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

            if (result.isSuccess()) {
                markNode(ne, markRequest("SUCCESS", r -> {
                    r.setOutputInfo(JsonUtils.toJSONString(result));
                    r.setEndTime(LocalDateTime.now());
                    r.setDurationMs(durationMs);
                }));
                savePythonLogs(ne.getId(), ne.getExecutionId(), logLines);

                DagInfo dag = getDag(dagId);
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
                String err = result.getStderr();
                if (!StringUtils.hasText(err)) err = "Python 执行失败";
                final String errMsg = err;
                markNode(ne, markRequest("FAILED", r -> {
                    r.setErrorMessage(errMsg);
                    r.setEndTime(LocalDateTime.now());
                    r.setDurationMs(durationMs);
                }));
                savePythonLogs(ne.getId(), ne.getExecutionId(), logLines);
                dagExecutionSyncService.finalizeIfAllDone(ne.getExecutionId());
                throw new BusinessException(ErrorCode.DAG_NODE_EXECUTE_FAILED, err);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Python 节点回调异常: nodeId={}", nodeId, e);
            markNode(ne, failedMark(startTime, e.getMessage()));
            savePythonLogs(ne.getId(), ne.getExecutionId(), logLines);
            dagExecutionSyncService.finalizeIfAllDone(ne.getExecutionId());
            throw new BusinessException(ErrorCode.DAG_NODE_EXECUTE_FAILED, "Python 节点执行失败: " + e.getMessage());
        }
    }

    private PythonNodeConfig parsePythonConfig(String configJson) {
        try {
            return JsonUtils.parseObject(configJson, PythonNodeConfig.class);
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
        // PowerJob 流程：dagExecutionId 直传（initParams / wfInstanceId 补齐）
        Long dagExecutionId = longOf(body.get("dagExecutionId"));
        if (nodeId == null || dagId == null || dagExecutionId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 nodeId / dagId / dagExecutionId");
        }

        NodeExecutionInfo ne = resolveLookup(nodeId, dagExecutionId).nodeExecution;
        if (ne == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "node_execution 不存在: " + nodeId);
        }
        // 条件节点自身也可能处于非命中分支（条件嵌套），先过 gate
        if (skipIfConditionGated(ne, dagId)) {
            return Result.ok(Map.of("branchIndex", -1, "skipped", true));
        }

        markNode(ne, markRequest("RUNNING", r -> r.setStartTime(LocalDateTime.now())));

        // 求值过程可能抛异常（配置缺失/表达式错误等），必须兜底回写终态，避免永久卡 RUNNING
        LocalDateTime startTime = LocalDateTime.now();
        try {
            DagNodeInfo node = getDagNode(dagId, nodeId);
            if (node == null || !StringUtils.hasText(node.getConfig())) {
                throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID, "条件节点配置缺失: " + nodeId);
            }
            ConditionNodeConfig config = parseConditionConfig(node.getConfig());
            if (config.getBranches() == null || config.getBranches().size() < 2) {
                throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID, "条件分支至少 2 个: " + nodeId);
            }

            Map<String, Object> vars = buildConditionContext(ne.getExecutionId(), dagId, nodeId);
            int branchIndex = evaluateBranches(config.getBranches(), vars);
            ConditionNodeConfig.ConditionBranch selected = config.getBranches().get(branchIndex);

            markNode(ne, markRequest("SUCCESS", r -> {
                r.setOutputInfo(JsonUtils.toJSONString(Map.of(
                        "branchIndex", branchIndex,
                        "nextNodeId", selected.getNextNodeId(),
                        "branchName", selected.getBranchName())));
                r.setEndTime(LocalDateTime.now());
                r.setDurationMs(Duration.between(startTime, LocalDateTime.now()).toMillis());
            }));

            logger.info("条件分支求值: executionId={}, nodeId={}, branchIndex={}, nextNodeId={}",
                    ne.getExecutionId(), nodeId, branchIndex, selected.getNextNodeId());
            finalizeExecutionQuietly(ne.getExecutionId());
            return Result.ok(Map.of("branchIndex", branchIndex, "nextNodeId", selected.getNextNodeId()));
        } catch (Exception e) {
            logger.error("条件节点求值失败: executionId={}, nodeId={}", ne.getExecutionId(), nodeId, e);
            markNode(ne, failedMark(startTime, e.getMessage()));
            finalizeExecutionQuietly(ne.getExecutionId());
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID,
                    "条件节点求值失败: " + e.getMessage(), e);
        }
    }

    private ConditionNodeConfig parseConditionConfig(String configJson) {
        try {
            return JsonUtils.parseObject(configJson, ConditionNodeConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CONDITION_CONFIG_INVALID,
                    "条件节点配置解析失败: " + e.getMessage());
        }
    }

    /**
     * 条件分支 gate：命中非命中分支（或上游全被跳过）的节点标记 SKIPPED 并收尾。
     * @return true 表示本节点应被跳过（已标记 SKIPPED）
     */
    private boolean skipIfConditionGated(NodeExecutionInfo ne, Long dagId) {
        if (ne == null) {
            return false;
        }
        if (!shouldSkipNode(ne.getExecutionId(), ne.getNodeId(), dagId)) {
            return false;
        }
        LocalDateTime endTime = LocalDateTime.now();
        markNode(ne, markRequest("SKIPPED", r -> {
            r.setEndTime(endTime);
            if (ne.getStartTime() != null) {
                r.setDurationMs(Duration.between(ne.getStartTime(), endTime).toMillis());
            }
        }));
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
            List<DagNodeInfo> nodes = listDagNodes(dagId);
            boolean hasCondition = nodes.stream()
                    .anyMatch(n -> "CONDITION".equalsIgnoreCase(n.getNodeType()));
            if (!hasCondition) {
                return false;
            }
            Map<String, String> nodeTypeMap = nodes.stream()
                    .collect(Collectors.toMap(DagNodeInfo::getNodeId, DagNodeInfo::getNodeType, (a, b) -> a));
            List<DagEdgeInfo> incoming = listDagEdges(dagId).stream()
                    .filter(e -> nodeId.equals(e.getTargetNodeId()))
                    .toList();
            if (incoming.isEmpty()) {
                return false;
            }
            Map<String, NodeExecutionInfo> neByNodeId = listExecutionNodes(executionId).stream()
                    .collect(Collectors.toMap(NodeExecutionInfo::getNodeId, n -> n, (a, b) -> a));

            boolean anyActive = false;
            for (DagEdgeInfo edge : incoming) {
                String srcNodeId = edge.getSourceNodeId();
                NodeExecutionInfo srcNe = neByNodeId.get(srcNodeId);
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
            return JsonUtils.getString(JsonUtils.parseObject(outputInfo), "nextNodeId");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建条件求值上下文：
     * - upstream：以「前驱节点名」为键的嵌套 map，每个前驱独立子 map（row_count / status / sql_type /
     *   target_table 等），顶层同时保留最后一个遍历前驱的 row_count / status 兼容旧写法
     * - DAG 参数平铺（如 biz_date）
     * - 系统变量：current_time
     */
    private Map<String, Object> buildConditionContext(Long executionId, Long dagId, String nodeId) {
        Map<String, Object> vars = new HashMap<>();
        Map<String, Object> upstream = new HashMap<>();
        List<String> predNodeIds = listDagEdges(dagId).stream()
                .filter(e -> nodeId.equals(e.getTargetNodeId()))
                .map(DagEdgeInfo::getSourceNodeId)
                .toList();
        if (!predNodeIds.isEmpty()) {
            listExecutionNodes(executionId).stream()
                    .filter(n -> predNodeIds.contains(n.getNodeId()))
                    .forEach(pred -> {
                        Map<String, Object> out = parseJsonMap(pred.getOutputInfo());
                        out.put("status", pred.getStatus());
                        normalizeRowCount(out);
                        // 嵌套结构：以节点名为键，支持 ${upstream['节点名'].row_count} 精确取值
                        if (StringUtils.hasText(pred.getNodeName())) {
                            upstream.put(pred.getNodeName(), out);
                        }
                        // 顶层兼容旧写法：取最后一个遍历前驱的值
                        upstream.putAll(out);
                    });
        }
        vars.put("upstream", upstream);

        DagExecutionInfo execution = getExecution(executionId);
        Map<String, Object> dagParams = dagParameterResolver.resolveParams(
                dagId, execution != null ? parseJsonMap(execution.getResolvedParams()) : null);
        dagParams.forEach(vars::put);

        // dag_id 为内部主键，无业务语义，不在条件表达式中暴露（参数解析层仍保留供 SQL 占位符使用）
        vars.remove("dag_id");
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
     * 节点执行记录定位（PowerJob 流程）：dagExecutionId 由 handler 直传（initParams 直给或按
     * wfInstanceId 补齐后直传），按执行记录直查节点；为空时无法定位，返回空。
     */
    private NodeExecutionLookup resolveLookup(String nodeId, Long dagExecutionId) {
        if (dagExecutionId == null) {
            return new NodeExecutionLookup(null);
        }
        NodeExecutionInfo ne = listExecutionNodes(dagExecutionId).stream()
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst().orElse(null);
        return new NodeExecutionLookup(ne);
    }

    /**
     * PowerJob cron 触发场景：initParams 无 dagExecutionId 时，按 wfInstanceId 调 engineering
     * ensure-execution 端点补齐 dag_execution + 全量 WAITING node_execution（服务端按
     * powerjob_wf_instance_id 列幂等，按 wfInstanceId 双检加锁），返回执行记录 id。
     * fail-fast：端点不可达/降级返回空直接抛错，节点执行可见失败。
     * Sprint 7 NG5：嵌套工作流（同步子 DAG）场景透传父执行 ID（parentDagExecutionId），
     * engineering 据此把父节点 paramMappings 映射进子执行记录 resolvedParams；cron 触发传 null。
     *
     * @return execution id；wfInstanceId 为空时返回 null
     */
    public Long ensureExecutionByWfInstance(Long dagId, Long wfInstanceId, Long parentDagExecutionId) {
        if (wfInstanceId == null) {
            return null;
        }
        EnsureDagExecutionRequest request = new EnsureDagExecutionRequest();
        request.setDagId(dagId);
        request.setWfInstanceId(wfInstanceId);
        request.setParentDagExecutionId(parentDagExecutionId);
        Long executionId;
        try {
            Result<Long> result = dagExecutionApi.ensureExecution(request);
            executionId = result == null ? null : result.data();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "按 wfInstanceId 补齐执行记录失败（engineering 不可达）: dagId=" + dagId
                            + ", wfInstanceId=" + wfInstanceId + ": " + e.getMessage(), e);
        }
        if (executionId == null) {
            // 熔断降级（fallback 返回空 data）
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "按 wfInstanceId 补齐执行记录失败: dagId=" + dagId + ", wfInstanceId=" + wfInstanceId);
        }
        logger.info("按 wfInstanceId 补齐执行记录: dagId={}, wfInstanceId={}, executionId={}",
                dagId, wfInstanceId, executionId);
        return executionId;
    }

    /**
     * 判断执行记录是否属于指定 DAG。
     * 嵌套子 DAG（NESTED_WORKFLOW）场景：子工作流实例继承父工作流的 initParams，
     * 其中的 dagExecutionId 属于父 DAG——子 DAG 节点 handler 必须用本校验识别后改走
     * ensureExecutionByWfInstance 补齐子 DAG 自己的执行记录。
     */
    public boolean executionBelongsToDag(Long dagExecutionId, Long dagId) {
        if (dagExecutionId == null || dagId == null) {
            return false;
        }
        try {
            Result<DagExecutionInfo> result = dagExecutionApi.getById(dagExecutionId);
            DagExecutionInfo info = result == null ? null : result.data();
            return info != null && dagId.equals(info.getDagId());
        } catch (Exception e) {
            logger.warn("执行记录归属校验失败（按不属于处理）: dagExecutionId={}, dagId={}: {}",
                    dagExecutionId, dagId, e.getMessage());
            return false;
        }
    }

    private void finalizeExecutionQuietly(Long executionId) {
        try {
            dagExecutionSyncService.finalizeIfAllDone(executionId);
        } catch (Exception e) {
            logger.warn("节点回调后立即收尾 dag_execution 失败（由定时同步兜底）: executionId={}", executionId, e);
        }
    }

    // ==================== 远程读写辅助（RemoteCalls 统一降级） ====================

    private DagExecutionInfo getExecution(Long executionId) {
        if (executionId == null) return null;
        return RemoteCalls.execute("engineering.dag-execution.get", () -> {
            Result<DagExecutionInfo> result = dagExecutionApi.getById(executionId);
            return result == null ? null : result.data();
        }, null);
    }

    private List<NodeExecutionInfo> listExecutionNodes(Long executionId) {
        return RemoteCalls.execute("engineering.dag-execution.nodes", () -> {
            Result<List<NodeExecutionInfo>> result = dagExecutionApi.listNodes(executionId);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
    }

    private DagInfo getDag(Long dagId) {
        if (dagId == null) return null;
        return RemoteCalls.execute("engineering.dag.get", () -> {
            Result<DagInfo> result = dagApi.getById(dagId);
            return result == null ? null : result.data();
        }, null);
    }

    private DagNodeInfo getDagNode(Long dagId, String nodeId) {
        return RemoteCalls.execute("engineering.dag.node-by-node-id", () -> {
            Result<DagNodeInfo> result = dagApi.getNodeByNodeId(dagId, nodeId);
            return result == null ? null : result.data();
        }, null);
    }

    private List<DagNodeInfo> listDagNodes(Long dagId) {
        return RemoteCalls.execute("engineering.dag.nodes", () -> {
            Result<List<DagNodeInfo>> result = dagApi.listNodes(dagId);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
    }

    private List<DagEdgeInfo> listDagEdges(Long dagId) {
        return RemoteCalls.execute("engineering.dag.edges", () -> {
            Result<List<DagEdgeInfo>> result = dagApi.listEdges(dagId);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
    }

    /**
     * 构建节点状态机写入请求（仅携带本次真正变更的字段，避免覆盖并发写入的其他字段）。
     */
    private NodeExecutionMarkRequest markRequest(String status, Consumer<NodeExecutionMarkRequest> filler) {
        NodeExecutionMarkRequest request = new NodeExecutionMarkRequest();
        request.setStatus(status);
        if (filler != null) {
            filler.accept(request);
        }
        return request;
    }

    /** 失败终态写入请求（FAILED + errorMessage + endTime + durationMs） */
    private NodeExecutionMarkRequest failedMark(LocalDateTime startTime, String errorMessage) {
        return markRequest("FAILED", r -> {
            r.setErrorMessage(errorMessage);
            r.setEndTime(LocalDateTime.now());
            r.setDurationMs(Duration.between(startTime, LocalDateTime.now()).toMillis());
        });
    }

    /**
     * 节点状态机写入：expectedStatus 取回调读取时的快照值（条件更新语义保留——
     * 状态已被同步器/收割器并发翻转时不覆盖，与原 updateById 乐观锁的防覆盖效果一致）。
     * 写入成功后同步本地快照，供后续字段计算（duration 等）与日志使用；
     * 远程失败经 RemoteCalls 降级（节点状态由 DS 同步器兜底收敛）。
     */
    private boolean markNode(NodeExecutionInfo ne, NodeExecutionMarkRequest request) {
        request.setExpectedStatus(ne.getStatus());
        Boolean ok = RemoteCalls.execute("engineering.node-execution.mark", () -> {
            Result<Boolean> result = dagExecutionApi.markNode(ne.getId(), request);
            return result != null && Boolean.TRUE.equals(result.data());
        }, false);
        // 本地快照同步为写入后的值（无论远程是否生效，后续逻辑按新状态继续，DB 以实际写入为准）
        if (request.getStatus() != null) ne.setStatus(request.getStatus());
        if (request.getStartTime() != null) ne.setStartTime(request.getStartTime());
        if (request.getEndTime() != null) ne.setEndTime(request.getEndTime());
        if (request.getDurationMs() != null) ne.setDurationMs(request.getDurationMs());
        if (request.getErrorMessage() != null) ne.setErrorMessage(request.getErrorMessage());
        if (request.getOutputInfo() != null) ne.setOutputInfo(request.getOutputInfo());
        if (request.getSyncJobId() != null) ne.setSyncJobId(request.getSyncJobId());
        if (request.getSyncJobHistoryId() != null) ne.setSyncJobHistoryId(request.getSyncJobHistoryId());
        return ok;
    }

    private static NodeLogAppendRequest.Entry logEntry(String level, String message) {
        NodeLogAppendRequest.Entry entry = new NodeLogAppendRequest.Entry();
        entry.setLevel(level);
        entry.setMessage(message);
        return entry;
    }

    private void saveNodeLogs(Long nodeExecutionId, Long executionId, String nodeId, List<String> lines) {
        if (lines == null || lines.isEmpty() || nodeExecutionId == null) return;
        List<NodeLogAppendRequest.Entry> entries = lines.stream()
                .map(line -> logEntry(line.startsWith("[ERROR]") ? "ERROR" : "INFO", line))
                .toList();
        RemoteCalls.execute("engineering.node-log.append",
                () -> dagExecutionApi.appendNodeLogs(nodeExecutionId, logRequest(executionId, entries)));
    }

    private void savePythonLogs(Long nodeExecutionId, Long executionId, List<NodeLogAppendRequest.Entry> logLines) {
        if (logLines == null || logLines.isEmpty() || nodeExecutionId == null) {
            return;
        }
        RemoteCalls.execute("engineering.node-log.append",
                () -> dagExecutionApi.appendNodeLogs(nodeExecutionId, logRequest(executionId, logLines)));
    }

    private NodeLogAppendRequest logRequest(Long executionId, List<NodeLogAppendRequest.Entry> entries) {
        NodeLogAppendRequest request = new NodeLogAppendRequest();
        request.setExecutionId(executionId);
        request.setEntries(entries);
        return request;
    }

    private void recordSqlLineage(String sqlContent, Long dagId, String nodeId, Long executionId) {
        try {
            DagInfo dag = getDag(dagId);
            DagNodeInfo node = getDagNode(dagId, nodeId);
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
            return JsonUtils.parseObject(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
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

    private record NodeExecutionLookup(NodeExecutionInfo nodeExecution) {
    }
}
