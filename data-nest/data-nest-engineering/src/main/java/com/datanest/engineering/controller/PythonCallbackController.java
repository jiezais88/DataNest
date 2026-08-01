package com.datanest.engineering.controller;

import com.alibaba.fastjson2.JSON;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.PythonCallbackRequest;
import com.datanest.engineering.dto.PythonNodeConfig;
import com.datanest.engineering.service.DagParameterService;
import com.datanest.engineering.service.NodeExecutionLogService;
import com.datanest.task.core.dto.PythonExecuteResult;
import com.datanest.task.core.entity.Dag;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.DagMapper;
import com.datanest.task.core.mapper.DagNodeMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.datanest.task.core.service.DagExecutionSyncService;
import com.datanest.task.core.service.MetadataRegistrationService;
import com.datanest.task.core.service.PythonExecutor;
import com.datanest.task.core.service.SqlLineageExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * PYTHON 节点内部回调接口
 */
@RestController
@RequestMapping("/dev/internal")
public class PythonCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(PythonCallbackController.class);

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagMapper dagMapper;
    private final DagNodeMapper dagNodeMapper;
    private final PythonExecutor pythonExecutor;
    private final DagParameterService dagParameterService;
    private final MetadataRegistrationService metadataRegistrationService;
    private final NodeExecutionLogService nodeExecutionLogService;
    private final DagExecutionSyncService dagExecutionSyncService;
    private final SqlLineageExtractor sqlLineageExtractor;

    public PythonCallbackController(DagExecutionMapper dagExecutionMapper, NodeExecutionMapper nodeExecutionMapper,
                                    DagMapper dagMapper, DagNodeMapper dagNodeMapper,
                                    PythonExecutor pythonExecutor,
                                    DagParameterService dagParameterService,
                                    MetadataRegistrationService metadataRegistrationService,
                                    NodeExecutionLogService nodeExecutionLogService,
                                    DagExecutionSyncService dagExecutionSyncService,
                                    SqlLineageExtractor sqlLineageExtractor) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagMapper = dagMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.pythonExecutor = pythonExecutor;
        this.dagParameterService = dagParameterService;
        this.metadataRegistrationService = metadataRegistrationService;
        this.nodeExecutionLogService = nodeExecutionLogService;
        this.dagExecutionSyncService = dagExecutionSyncService;
        this.sqlLineageExtractor = sqlLineageExtractor;
    }

    @PostMapping("/python/callback")
    public Result<Map<String, Object>> pythonCallback(@RequestBody PythonCallbackRequest request) {
        Long dagId = request.getDagId();
        Long executionId = request.getExecutionId();
        String nodeId = request.getNodeId();
        if (dagId == null || executionId == null || nodeId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "缺少 dagId / executionId / nodeId");
        }

        DagNode node = dagNodeMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DagNode>()
                                .eq("dag_id", dagId).eq("node_id", nodeId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (node == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "节点不存在: " + nodeId);
        }

        PythonNodeConfig config = parseConfig(node.getConfig());
        if (!StringUtils.hasText(config.getPythonScript())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Python 脚本为空: " + nodeId);
        }

        NodeExecution ne = nodeExecutionMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NodeExecution>()
                                .eq("execution_id", executionId).eq("node_id", nodeId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (ne == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "node_execution 不存在: " + nodeId);
        }

        ne.setStatus("RUNNING");
        ne.setStartTime(LocalDateTime.now());
        nodeExecutionMapper.updateById(ne);

        DagExecution execution = dagExecutionMapper.selectById(executionId);
        Map<String, Object> params = dagParameterService.resolveParams(
                dagId, execution != null ? parseJsonMap(execution.getResolvedParams()) : null);
        String script = dagParameterService.replacePlaceholders(config.getPythonScript(), params);

        List<NodeExecutionLogService.LogLine> logLines = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> logCollector = line -> logLines.add(
                new NodeExecutionLogService.LogLine(line.startsWith("[STDERR]") ? "ERROR" : "INFO", line));

        LocalDateTime startTime = LocalDateTime.now();
        try {
            PythonExecuteResult result = pythonExecutor.execute(
                    script,
                    new PythonExecutor.PythonContext(params, logCollector),
                    config.getTimeoutMinutes(),
                    config.getMemoryLimitMb());

            long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            ne.setDurationMs(durationMs);
            ne.setEndTime(LocalDateTime.now());

            if (result.isSuccess()) {
                ne.setStatus("SUCCESS");
                ne.setOutputInfo(JSON.toJSONString(result));
                nodeExecutionMapper.updateById(ne);
                saveLogs(executionId, nodeId, logLines);

                Dag dag = dagMapper.selectById(dagId);
                String dagName = dag != null ? dag.getName() : null;

                // 元数据注册 + 血缘
                List<String> outputTables = result.getOutputTables();
                if (outputTables != null && !outputTables.isEmpty()) {
                    for (String table : outputTables) {
                        try {
                            metadataRegistrationService.registerFromPython(table, dagId, dagName, nodeId, node.getNodeName(), executionId);
                        } catch (Exception e) {
                            logger.warn("Python 输出表元数据注册失败: table={}", table, e);
                        }
                    }
                    recordPythonLineage(dagId, dagName, nodeId, node.getNodeName(), executionId, outputTables);
                }

                dagExecutionSyncService.finalizeIfAllDone(executionId);
                return Result.ok(Map.of("outputTables", outputTables == null ? List.of() : outputTables,
                        "durationMs", durationMs));
            } else {
                ne.setStatus("FAILED");
                String err = result.getStderr();
                if (!StringUtils.hasText(err)) err = "Python 执行失败";
                ne.setErrorMessage(err);
                nodeExecutionMapper.updateById(ne);
                saveLogs(executionId, nodeId, logLines);
                dagExecutionSyncService.finalizeIfAllDone(executionId);
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
            saveLogs(executionId, nodeId, logLines);
            dagExecutionSyncService.finalizeIfAllDone(executionId);
            throw new BusinessException(ErrorCode.DAG_NODE_EXECUTE_FAILED, "Python 节点执行失败: " + e.getMessage());
        }
    }

    private PythonNodeConfig parseConfig(String configJson) {
        try {
            return JSON.parseObject(configJson, PythonNodeConfig.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Python 节点配置解析失败: " + e.getMessage());
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return JSON.parseObject(json, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private void saveLogs(Long executionId, String nodeId, List<NodeExecutionLogService.LogLine> logLines) {
        if (logLines == null || logLines.isEmpty()) {
            return;
        }
        try {
            nodeExecutionLogService.saveLogs(executionId, nodeId, logLines);
        } catch (Exception e) {
            logger.warn("保存 Python 节点日志失败", e);
        }
    }

    private void recordPythonLineage(Long dagId, String dagName, String nodeId, String nodeName,
                                     Long executionId, List<String> outputTables) {
        try {
            sqlLineageExtractor.recordPythonLineage(
                    outputTables, dagId, dagName, nodeId, nodeName, executionId);
        } catch (Exception e) {
            logger.warn("记录 Python 血缘失败", e);
        }
    }
}
