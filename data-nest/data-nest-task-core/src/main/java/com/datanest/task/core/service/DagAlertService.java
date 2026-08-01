package com.datanest.task.core.service;

import com.alibaba.fastjson2.JSON;
import com.datanest.task.core.entity.*;
import com.datanest.task.core.mapper.DagAlertConfigMapper;
import com.datanest.task.core.mapper.DagAlertHistoryMapper;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.DagMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * DAG 告警触发服务
 * 下沉到 task-core，供 engineering-service 与 data-nest-job 共用。
 */
@Service
public class DagAlertService {

    private static final Logger logger = LoggerFactory.getLogger(DagAlertService.class);

    private final DagAlertConfigMapper dagAlertConfigMapper;
    private final DagAlertHistoryMapper dagAlertHistoryMapper;
    private final DagMapper dagMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final MailService mailService;

    public DagAlertService(DagAlertConfigMapper dagAlertConfigMapper,
                           DagAlertHistoryMapper dagAlertHistoryMapper,
                           DagMapper dagMapper,
                           DagExecutionMapper dagExecutionMapper,
                           MailService mailService) {
        this.dagAlertConfigMapper = dagAlertConfigMapper;
        this.dagAlertHistoryMapper = dagAlertHistoryMapper;
        this.dagMapper = dagMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.mailService = mailService;
    }

    /**
     * DAG 执行失败时触发。
     */
    public void onDagFailed(DagExecution execution, List<NodeExecution> failedNodes) {
        DagAlertConfig config = resolveConfig(execution.getDagId());
        if (config == null || !isEnabled(config) || !contains(config, "FAILURE")) {
            return;
        }
        if (existsHistory(execution.getId(), null, "FAILURE")) {
            return;
        }

        Dag dag = dagMapper.selectById(execution.getDagId());
        String dagName = dag == null ? "未知 DAG" : dag.getName();
        String failedNodeNames = failedNodes == null || failedNodes.isEmpty()
                ? "-"
                : String.join("、", failedNodes.stream().map(NodeExecution::getNodeName).toList());
        String firstError = failedNodes == null || failedNodes.isEmpty()
                ? "-"
                : failedNodes.stream()
                .filter(n -> StringUtils.hasText(n.getErrorMessage()))
                .findFirst()
                .map(NodeExecution::getErrorMessage)
                .orElse("-");

        String subject = String.format("[DataNest 告警] DAG「%s」执行失败", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + format(execution.getStartTime()),
                "失败节点：" + failedNodeNames,
                "错误摘要：" + firstError,
                "查看详情：" + buildExecutionUrl(execution.getId()));

        mailService.send(config.getRecipients(), subject, body);
        saveHistory(execution.getId(), null, "FAILURE", config.getRecipients());
    }

    /**
     * 节点超时时触发。
     */
    public void onNodeTimeout(NodeExecution node, Long dagId) {
        DagAlertConfig config = resolveConfig(dagId);
        if (config == null || !isEnabled(config) || !contains(config, "TIMEOUT")) {
            return;
        }
        if (existsHistory(node.getExecutionId(), node.getNodeId(), "TIMEOUT")) {
            return;
        }

        Dag dag = dagMapper.selectById(dagId);
        String dagName = dag == null ? "未知 DAG" : dag.getName();
        DagExecution execution = dagExecutionMapper.selectById(node.getExecutionId());
        String executionTime = format(execution != null ? execution.getStartTime() : null);

        String subject = String.format("[DataNest 告警] DAG「%s」节点执行超时", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + executionTime,
                "节点：" + node.getNodeName() + "（" + node.getNodeId() + "）",
                "节点类型：" + node.getNodeType(),
                "开始时间：" + format(node.getStartTime()),
                "当前状态：RUNNING",
                "查看详情：" + buildExecutionUrl(node.getExecutionId()));

        mailService.send(config.getRecipients(), subject, body);
        saveHistory(node.getExecutionId(), node.getNodeId(), "TIMEOUT", config.getRecipients());
    }

    /**
     * DAG 执行成功时触发（如果配置了 SUCCESS）。
     */
    public void onDagSuccess(DagExecution execution) {
        DagAlertConfig config = resolveConfig(execution.getDagId());
        if (config == null || !isEnabled(config) || !contains(config, "SUCCESS")) {
            return;
        }
        if (existsHistory(execution.getId(), null, "SUCCESS")) {
            return;
        }

        Dag dag = dagMapper.selectById(execution.getDagId());
        String dagName = dag == null ? "未知 DAG" : dag.getName();
        String subject = String.format("[DataNest 通知] DAG「%s」执行成功", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + format(execution.getStartTime()),
                "结束时间：" + format(execution.getEndTime()),
                "查看详情：" + buildExecutionUrl(execution.getId()));

        mailService.send(config.getRecipients(), subject, body);
        saveHistory(execution.getId(), null, "SUCCESS", config.getRecipients());
    }

    /**
     * Sprint 4 review：按 DAG 取告警配置，优先专用配置，无则回退全局默认。
     */
    public DagAlertConfig resolveConfig(Long dagId) {
        if (dagId != null) {
            DagAlertConfig dedicated = dagAlertConfigMapper.selectByDagId(dagId);
            if (dedicated != null) {
                return dedicated;
            }
        }
        return dagAlertConfigMapper.selectGlobal();
    }

    private boolean isEnabled(DagAlertConfig config) {
        return config.getEnabled() != null && config.getEnabled() == 1;
    }

    private boolean contains(DagAlertConfig config, String condition) {
        if (!StringUtils.hasText(config.getTriggerConditions())) {
            return false;
        }
        try {
            List<String> conditions = JSON.parseArray(config.getTriggerConditions(), String.class);
            return conditions != null && conditions.contains(condition);
        } catch (Exception e) {
            logger.warn("告警触发条件 JSON 解析失败: {}", config.getTriggerConditions());
            return false;
        }
    }

    private boolean existsHistory(Long executionId, String nodeId, String alertType) {
        return dagAlertHistoryMapper.countByExecutionAndType(executionId, nodeId, alertType) > 0;
    }

    private void saveHistory(Long executionId, String nodeId, String alertType, String recipients) {
        DagAlertHistory history = new DagAlertHistory();
        history.setExecutionId(executionId);
        history.setNodeId(nodeId);
        history.setAlertType(alertType);
        history.setRecipients(recipients);
        history.setSentAt(LocalDateTime.now());
        dagAlertHistoryMapper.insert(history);
    }

    private String format(LocalDateTime time) {
        if (time == null) return "-";
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String buildExecutionUrl(Long executionId) {
        // 占位：实际由前端部署域名决定，可通过配置覆盖
        return "http://localhost:3000/engineering/dags/executions/" + executionId;
    }
}
