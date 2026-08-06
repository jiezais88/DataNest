package com.datanest.alert.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.datanest.alert.api.dto.DagExecutionInfo;
import com.datanest.alert.api.dto.DagNodeTimeoutRequest;
import com.datanest.alert.api.dto.NodeExecutionInfo;
import com.datanest.alert.constant.AlertConstants;
import com.datanest.alert.entity.AlertHistory;
import com.datanest.alert.entity.DagAlertConfig;
import com.datanest.alert.entity.DagAlertHistory;
import com.datanest.alert.mapper.AlertHistoryMapper;
import com.datanest.alert.mapper.DagAlertConfigMapper;
import com.datanest.alert.mapper.DagAlertHistoryMapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringObjectApi;
import com.datanest.engineering.api.dto.ObjectNameRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * DAG 告警触发服务。
 * 优先走通用告警规则（alert_rule），未命中规则时回退 dag_alert_config。
 * 微服务化改造：不再本地查询 dag / dag_execution 表——
 * dagName 通过 engineering 内部接口解析，执行/节点时间由调用方在请求 payload 中传入。
 */
@Service
public class DagAlertService {

    private static final Logger logger = LoggerFactory.getLogger(DagAlertService.class);

    private final DagAlertConfigMapper dagAlertConfigMapper;
    private final DagAlertHistoryMapper dagAlertHistoryMapper;
    private final AlertHistoryMapper alertHistoryMapper;
    private final EngineeringObjectApi engineeringObjectApi;
    private final MailService mailService;
    private final AlertFiringService alertFiringService;

    public DagAlertService(DagAlertConfigMapper dagAlertConfigMapper,
                           DagAlertHistoryMapper dagAlertHistoryMapper,
                           AlertHistoryMapper alertHistoryMapper,
                           EngineeringObjectApi engineeringObjectApi,
                           MailService mailService,
                           AlertFiringService alertFiringService) {
        this.dagAlertConfigMapper = dagAlertConfigMapper;
        this.dagAlertHistoryMapper = dagAlertHistoryMapper;
        this.alertHistoryMapper = alertHistoryMapper;
        this.engineeringObjectApi = engineeringObjectApi;
        this.mailService = mailService;
        this.alertFiringService = alertFiringService;
    }

    /**
     * DAG 执行失败时触发。
     * 优先走通用告警规则（alert_rule），未命中规则时回退 dag_alert_config。
     */
    public void onDagFailed(DagExecutionInfo execution, List<NodeExecutionInfo> failedNodes) {
        String firstError = failedNodes == null || failedNodes.isEmpty()
                ? "-"
                : failedNodes.stream()
                .filter(n -> StringUtils.hasText(n.getErrorMessage()))
                .findFirst()
                .map(NodeExecutionInfo::getErrorMessage)
                .orElse("-");
        if (alertFiringService.fire("DAG", execution.getDagId(), "FAILURE", firstError)) {
            return;
        }

        DagAlertConfig config = resolveConfig(execution.getDagId());
        if (config == null || !isEnabled(config) || !contains(config, "FAILURE")) {
            return;
        }
        if (existsHistory(execution.getId(), null, "FAILURE")) {
            return;
        }

        String dagName = resolveDagName(execution.getDagId());
        String failedNodeNames = failedNodes == null || failedNodes.isEmpty()
                ? "-"
                : String.join("、", failedNodes.stream().map(NodeExecutionInfo::getNodeName).toList());

        String subject = String.format("[DataNest 告警] DAG「%s」执行失败", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + format(execution.getStartTime()),
                "失败节点：" + failedNodeNames,
                "错误摘要：" + firstError,
                "查看详情：" + buildExecutionUrl(execution.getId()));

        boolean sent = mailService.send(config.getRecipients(), subject, body);
        saveHistory(execution.getId(), execution.getDagId(), null, "FAILURE", config.getRecipients(), sent);
    }

    /**
     * 节点超时时触发（执行实例与节点时间均由 payload 传入）。
     */
    public void onNodeTimeout(DagNodeTimeoutRequest request) {
        if (alertFiringService.fire("DAG", request.getDagId(), "TIMEOUT",
                "节点「" + request.getNodeName() + "」执行超时")) {
            return;
        }

        DagAlertConfig config = resolveConfig(request.getDagId());
        if (config == null || !isEnabled(config) || !contains(config, "TIMEOUT")) {
            return;
        }
        if (existsHistory(request.getExecutionId(), request.getNodeId(), "TIMEOUT")) {
            return;
        }

        String dagName = resolveDagName(request.getDagId());
        String executionTime = format(request.getExecutionStartTime());

        String subject = String.format("[DataNest 告警] DAG「%s」节点执行超时", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + executionTime,
                "节点：" + request.getNodeName() + "（" + request.getNodeId() + "）",
                "节点类型：" + request.getNodeType(),
                "开始时间：" + format(request.getNodeStartTime()),
                "当前状态：RUNNING",
                "查看详情：" + buildExecutionUrl(request.getExecutionId()));

        boolean sent = mailService.send(config.getRecipients(), subject, body);
        saveHistory(request.getExecutionId(), request.getDagId(), request.getNodeId(), "TIMEOUT", config.getRecipients(), sent);
    }

    /**
     * DAG 执行成功时触发（如果配置了 SUCCESS）。
     */
    public void onDagSuccess(DagExecutionInfo execution) {
        if (alertFiringService.fire("DAG", execution.getDagId(), "SUCCESS", "DAG 执行成功")) {
            return;
        }

        DagAlertConfig config = resolveConfig(execution.getDagId());
        if (config == null || !isEnabled(config) || !contains(config, "SUCCESS")) {
            return;
        }
        if (existsHistory(execution.getId(), null, "SUCCESS")) {
            return;
        }

        String dagName = resolveDagName(execution.getDagId());
        String subject = String.format("[DataNest 通知] DAG「%s」执行成功", dagName);
        String body = String.join("\n",
                "DAG：" + dagName,
                "执行时间：" + format(execution.getStartTime()),
                "结束时间：" + format(execution.getEndTime()),
                "查看详情：" + buildExecutionUrl(execution.getId()));

        boolean sent = mailService.send(config.getRecipients(), subject, body);
        saveHistory(execution.getId(), execution.getDagId(), null, "SUCCESS", config.getRecipients(), sent);
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

    /**
     * 按 DAG 删除告警配置（engineering 删除 DAG/项目时经内部接口级联调用）。
     */
    public void deleteConfigByDag(Long dagId) {
        if (dagId == null) {
            return;
        }
        dagAlertConfigMapper.delete(new QueryWrapper<DagAlertConfig>().eq("dag_id", dagId));
    }

    /**
     * 按执行实例批量删除 DAG 告警发送历史（engineering 删除 DAG/项目时经内部接口级联调用）。
     */
    public void deleteHistoryByExecutions(List<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return;
        }
        dagAlertHistoryMapper.delete(new QueryWrapper<DagAlertHistory>().in("execution_id", executionIds));
    }

    /**
     * 通过 engineering 内部接口解析 DAG 名称；解析失败降级为「未知 DAG」并记 warn，不阻断告警发送。
     */
    private String resolveDagName(Long dagId) {
        if (dagId == null) {
            return "未知 DAG";
        }
        // RemoteCalls 统一降级：兜住熔断 fallback 之外的异常，warn + 计数后降级为「未知 DAG」
        return RemoteCalls.execute("engineering.names", () -> {
            ObjectNameRequest request = new ObjectNameRequest();
            request.setObjectType(AlertConstants.OBJECT_TYPE_DAG);
            request.setIds(List.of(dagId));
            Result<Map<Long, String>> result = engineeringObjectApi.names(request);
            if (result != null && result.data() != null && StringUtils.hasText(result.data().get(dagId))) {
                return result.data().get(dagId);
            }
            return "未知 DAG";
        }, "未知 DAG");
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

    private void saveHistory(Long executionId, Long dagId, String nodeId, String alertType,
                             String recipients, boolean sent) {
        DagAlertHistory history = new DagAlertHistory();
        history.setExecutionId(executionId);
        history.setNodeId(nodeId);
        history.setAlertType(alertType);
        history.setRecipients(recipients);
        history.setSentAt(LocalDateTime.now());
        dagAlertHistoryMapper.insert(history);

        // Sprint 5 测试补充：兼容回退告警同步写入统一 alert_history，供告警中心历史页展示
        // （alert_rule_id 为空，表示非 alert_rule 规则触发的回退告警）
        try {
            AlertHistory ah = new AlertHistory();
            ah.setId(IdWorker.getId());
            ah.setObjectType(AlertConstants.OBJECT_TYPE_DAG);
            ah.setObjectId(dagId);
            ah.setAlertType(alertType);
            ah.setRecipients(recipients);
            ah.setSendStatus(sent ? AlertConstants.SEND_STATUS_SUCCESS : AlertConstants.SEND_STATUS_FAILED);
            ah.setSentAt(LocalDateTime.now());
            alertHistoryMapper.insert(ah);
        } catch (Exception e) {
            logger.warn("兼容回退告警写入 alert_history 失败: dagId={}, alertType={}", dagId, alertType, e);
        }
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
