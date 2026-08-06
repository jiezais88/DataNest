package com.datanest.alert.controller;

import com.datanest.alert.api.dto.*;
import com.datanest.alert.entity.AlertHistory;
import com.datanest.alert.service.AlertFiringService;
import com.datanest.alert.service.AlertRuleService;
import com.datanest.alert.service.DagAlertService;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringObjectApi;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 告警服务内部接口（实现 alert-api 的 Feign 契约）。
 * <p>
 * 仅供服务间内部调用，路径挂在 context-path /alert 下（servlet path 以 /internal/ 开头），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@RestController
@RequestMapping("/internal")
public class AlertInternalController {

    /** 自动触发对象类型（与 quality_job.auto_trigger_object_type 对应） */
    private static final String OBJECT_TYPE_DAG_NODE = "DAG_NODE";

    private final AlertFiringService alertFiringService;
    private final DagAlertService dagAlertService;
    private final AlertRuleService alertRuleService;
    private final EngineeringObjectApi engineeringObjectApi;
    private final GovernanceObjectApi governanceObjectApi;

    public AlertInternalController(AlertFiringService alertFiringService,
                                   DagAlertService dagAlertService,
                                   AlertRuleService alertRuleService,
                                   EngineeringObjectApi engineeringObjectApi,
                                   GovernanceObjectApi governanceObjectApi) {
        this.alertFiringService = alertFiringService;
        this.dagAlertService = dagAlertService;
        this.alertRuleService = alertRuleService;
        this.engineeringObjectApi = engineeringObjectApi;
        this.governanceObjectApi = governanceObjectApi;
    }

    /** 触发单条告警 */
    @PostMapping("/fired")
    public Result<Boolean> fire(@RequestBody AlertFireRequest request) {
        return Result.ok(alertFiringService.fire(
                request.getObjectType(), request.getObjectId(), request.getAlertType(), request.getDetail()));
    }

    /** 批量触发告警（含质量批次 batchId） */
    @PostMapping("/fired/batch")
    public Result<Boolean> fireBatch(@RequestBody AlertFireBatchRequest request) {
        List<AlertFiringService.AlertItem> items = request.getItems() == null ? null
                : request.getItems().stream()
                .map(i -> new AlertFiringService.AlertItem(i.getLevel(), i.getRuleName(), i.getDetail()))
                .toList();
        return Result.ok(alertFiringService.fireBatch(
                request.getObjectType(), request.getObjectId(), request.getAlertType(), items, request.getBatchId()));
    }

    /**
     * DAG 执行完成通知：失败走 onDagFailed；成功走 onDagSuccess + 成功节点的质量任务自动触发。
     */
    @PostMapping("/dag-finished")
    public Result<Void> dagFinished(@RequestBody DagFinishedRequest request) {
        DagExecutionInfo execution = request.getExecution();
        List<NodeExecutionInfo> nodes = request.getNodes();
        if (execution == null || nodes == null) {
            return Result.ok(null);
        }
        String status = execution.getStatus();
        if ("FAILED".equalsIgnoreCase(status)) {
            List<NodeExecutionInfo> failedNodes = nodes.stream()
                    .filter(n -> "FAILED".equalsIgnoreCase(n.getStatus())
                            || "TERMINATED".equalsIgnoreCase(n.getStatus()))
                    .toList();
            dagAlertService.onDagFailed(execution, failedNodes);
        } else if ("SUCCESS".equalsIgnoreCase(status)) {
            dagAlertService.onDagSuccess(execution);
            triggerQualityOnSuccessNodes(execution, nodes);
        }
        return Result.ok(null);
    }

    /**
     * DAG 成功后，一次性反查全部成功节点的 dag_node.id，再批量触发绑定这些节点的质量任务自动检查。
     * 两次远程调用各自 try-catch 记 error 不中断，不影响 DAG 结果。
     */
    private void triggerQualityOnSuccessNodes(DagExecutionInfo execution, List<NodeExecutionInfo> nodes) {
        Long dagId = execution.getDagId();
        if (dagId == null) {
            return;
        }
        List<String> successNodeIds = nodes.stream()
                .filter(n -> "SUCCESS".equalsIgnoreCase(n.getStatus()) && n.getNodeId() != null)
                .map(NodeExecutionInfo::getNodeId)
                .distinct()
                .toList();
        if (successNodeIds.isEmpty()) {
            return;
        }
        // 一次批量解析 nodeId → dag_node.id；RemoteCalls 统一降级：失败返回空 Map（跳过本次触发，不影响 DAG 结果）
        Map<String, Long> dagNodeIdMap = RemoteCalls.execute("engineering.resolveDagNodeIds", () -> {
            Result<Map<String, Long>> result = engineeringObjectApi.resolveDagNodeIds(dagId, successNodeIds);
            return result == null || result.data() == null ? Map.<String, Long>of() : result.data();
        }, Map.of());
        List<Long> dagNodeIds = dagNodeIdMap.values().stream().filter(Objects::nonNull).distinct().toList();
        if (dagNodeIds.isEmpty()) {
            return;
        }
        // 一次批量触发质量任务自动检查；RemoteCalls 统一降级：失败 warn + 计数，不影响 DAG 结果
        RemoteCalls.execute("governance.qualityAutoTriggerBatch", () -> {
            QualityAutoTriggerBatchRequest triggerRequest = new QualityAutoTriggerBatchRequest();
            triggerRequest.setObjectType(OBJECT_TYPE_DAG_NODE);
            triggerRequest.setObjectIds(dagNodeIds);
            governanceObjectApi.qualityAutoTriggerBatch(triggerRequest);
        });
    }

    /** DAG 节点超时通知（时间取 payload） */
    @PostMapping("/dag-node-timeout")
    public Result<Void> dagNodeTimeout(@RequestBody DagNodeTimeoutRequest request) {
        dagAlertService.onNodeTimeout(request);
        return Result.ok(null);
    }

    /** 按对象删除告警规则 */
    @DeleteMapping("/rules/by-object")
    public Result<Void> deleteRuleByObject(@RequestParam String objectType, @RequestParam Long objectId) {
        alertRuleService.deleteByObject(objectType, objectId);
        return Result.ok(null);
    }

    /** 按对象查询引用它的告警规则名称列表（删除前引用校验用） */
    @GetMapping("/rules/by-object/names")
    public Result<List<String>> listRuleNamesByObject(@RequestParam String objectType, @RequestParam Long objectId) {
        return Result.ok(alertRuleService.listRuleNamesByObject(objectType, objectId));
    }

    /** 按质量批次查询告警历史 */
    @GetMapping("/histories/by-quality-batch")
    public Result<List<AlertHistoryDTO>> listByQualityBatch(@RequestParam Long batchId) {
        List<AlertHistoryDTO> list = alertRuleService.listHistoryByQualityBatch(batchId).stream()
                .map(this::toDTO)
                .toList();
        return Result.ok(list);
    }

    /** 清理指定天数之前的告警历史，返回删除条数 */
    @DeleteMapping("/histories/cleanup")
    public Result<Integer> cleanupHistories(@RequestParam int beforeDays) {
        return Result.ok(alertRuleService.cleanupHistory(beforeDays));
    }

    /** 按 DAG 解析生效的告警配置（专用配置优先，回退全局默认；无配置返回 null） */
    @GetMapping("/dag-alert-config/resolve")
    public Result<DagAlertConfigInfo> resolveDagAlertConfig(@RequestParam Long dagId) {
        com.datanest.alert.entity.DagAlertConfig config = dagAlertService.resolveConfig(dagId);
        if (config == null) {
            return Result.ok(null);
        }
        DagAlertConfigInfo info = new DagAlertConfigInfo();
        info.setEnabled(config.getEnabled());
        info.setTimeoutMinutes(config.getTimeoutMinutes());
        return Result.ok(info);
    }

    /** 按 DAG 删除告警配置（DAG 级联删除场景） */
    @DeleteMapping("/dag-alert-config/by-dag")
    public Result<Void> deleteDagAlertConfigByDag(@RequestParam Long dagId) {
        dagAlertService.deleteConfigByDag(dagId);
        return Result.ok(null);
    }

    /** 按执行实例批量删除 DAG 告警发送历史（DAG 级联删除场景） */
    @DeleteMapping("/dag-alert-histories/by-executions")
    public Result<Void> deleteDagAlertHistoriesByExecutions(@RequestParam List<Long> executionIds) {
        dagAlertService.deleteHistoryByExecutions(executionIds);
        return Result.ok(null);
    }

    /** 告警历史实体 → alert-api DTO */
    private AlertHistoryDTO toDTO(AlertHistory history) {
        AlertHistoryDTO dto = new AlertHistoryDTO();
        dto.setId(history.getId());
        dto.setAlertRuleId(history.getAlertRuleId());
        dto.setRuleName(history.getRuleName());
        dto.setObjectType(history.getObjectType());
        dto.setObjectId(history.getObjectId());
        dto.setQualityBatchId(history.getQualityBatchId());
        dto.setAlertType(history.getAlertType());
        dto.setRecipients(history.getRecipients());
        dto.setSendStatus(history.getSendStatus());
        dto.setSentAt(history.getSentAt());
        dto.setSummary(history.getSummary());
        return dto;
    }
}
