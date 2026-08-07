package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagApi;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.DagNodeInfo;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.governance.api.GovernanceOpsApi;
import com.datanest.governance.api.dto.AutoTriggerBindingRequest;
import com.datanest.governance.api.dto.AutoTriggeredBatchQueryRequest;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import com.datanest.governance.api.dto.QualityJobBindingDTO;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质量自动触发对账（微服务化阶段 2 补偿）。
 * <p>
 * DAG 成功后的质量自动触发由 alert-service 经 governance 内部接口完成，任一环失败则本次触发
 * 静默丢失。本 handler 定时扫描近期成功的 DAG 节点执行，找出「绑定了启用质量任务但缺
 * AUTO_TRIGGER 批次」的漏触发节点，经 governance 批量接口补发。
 * <p>
 * 微服务化 3.3：dag_execution 扫描 / node_execution 读取 / dag_node 解析改经
 * EngineeringDagExecutionApi / EngineeringDagApi 远程获取（RemoteCalls 降级本轮跳过）。
 * 微服务化 4.3：quality_job 绑定查询与 quality_check_batch 批次查重改经 GovernanceOpsApi
 * （auto-trigger-bindings / batches/auto-triggered-since），job 不再直连治理表。
 * <p>
 * 补发天然幂等：重复补发最多多跑一次质量检查；补发失败下轮再补（窗口内）。
 */
@Component
public class QualityAutoTriggerReconcileHandler {

    private static final Logger logger = LoggerFactory.getLogger(QualityAutoTriggerReconcileHandler.class);

    /** 扫描窗口：最近 2 小时内结束的执行 */
    private static final int WINDOW_HOURS = 2;
    /** 安全边距：只处理结束超过 5 分钟的执行，避开正常触发路径的在途执行 */
    private static final int SAFE_MARGIN_MINUTES = 5;
    /** 判定漏触发时，批次 created_at 相对节点 end_time 的容忍提前量 */
    private static final int BATCH_TOLERANCE_MINUTES = 1;
    /** 每轮扫描的 DAG 执行实例上限 */
    private static final int SCAN_LIMIT = 200;
    /** 每轮补发的 dagNodeId 上限 */
    private static final int REISSUE_LIMIT = 50;

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String OBJECT_TYPE_DAG_NODE = "DAG_NODE";

    private final EngineeringDagExecutionApi dagExecutionApi;
    private final EngineeringDagApi dagApi;
    private final GovernanceOpsApi governanceOpsApi;
    private final GovernanceObjectApi governanceObjectApi;

    public QualityAutoTriggerReconcileHandler(EngineeringDagExecutionApi dagExecutionApi,
                                              EngineeringDagApi dagApi,
                                              GovernanceOpsApi governanceOpsApi,
                                              GovernanceObjectApi governanceObjectApi) {
        this.dagExecutionApi = dagExecutionApi;
        this.dagApi = dagApi;
        this.governanceOpsApi = governanceOpsApi;
        this.governanceObjectApi = governanceObjectApi;
    }

    @XxlJob("qualityAutoTriggerReconcileHandler")
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        // 1. 扫描窗口内成功的 DAG 执行（succeeded-between 端点：SUCCESS 且 start_time 落在窗口内，id 升序）
        List<DagExecutionInfo> dags = RemoteCalls.execute("engineering.dag-execution.succeeded-between", () -> {
            Result<List<DagExecutionInfo>> result = dagExecutionApi.succeededBetween(
                    // 契约为 ISO 字符串（Feign ConversionService 会把 LocalDateTime 按 locale 格式化）
                    now.minusHours(WINDOW_HOURS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    now.minusMinutes(SAFE_MARGIN_MINUTES).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    SCAN_LIMIT);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
        if (dags.isEmpty()) {
            XxlJobHelper.handleSuccess("无待对账执行");
            return;
        }
        Map<Long, Long> dagIdByExecutionId = new HashMap<>();
        for (DagExecutionInfo dag : dags) {
            dagIdByExecutionId.put(dag.getId(), dag.getDagId());
        }

        // 2. 这些执行下 SUCCESS 的节点执行（逐执行远程读取；每轮执行数少，非热路径）
        List<NodeExecutionInfo> nodes = new ArrayList<>();
        for (Long executionId : dagIdByExecutionId.keySet()) {
            List<NodeExecutionInfo> executionNodes = RemoteCalls.execute("engineering.dag-execution.nodes", () -> {
                Result<List<NodeExecutionInfo>> result = dagExecutionApi.listNodes(executionId);
                return result == null || result.data() == null ? List.of() : result.data();
            }, List.of());
            for (NodeExecutionInfo node : executionNodes) {
                if (STATUS_SUCCESS.equalsIgnoreCase(node.getStatus())) {
                    nodes.add(node);
                }
            }
        }
        if (nodes.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + "，无成功节点");
            return;
        }

        // 3. 按 dag_id + node_id 解析 dag_node.id（按 dag 聚合远程调用，建 dagId→nodes 本地映射避免重复调）
        Set<Long> dagIds = new HashSet<>(dagIdByExecutionId.values());
        Map<String, Long> dagNodeIdByKey = new HashMap<>();
        for (Long dagId : dagIds) {
            List<DagNodeInfo> dagNodes = RemoteCalls.execute("engineering.dag.nodes", () -> {
                Result<List<DagNodeInfo>> result = dagApi.listNodes(dagId);
                return result == null || result.data() == null ? List.of() : result.data();
            }, List.of());
            for (DagNodeInfo dagNode : dagNodes) {
                dagNodeIdByKey.put(key(dagNode.getDagId(), dagNode.getNodeId()), dagNode.getId());
            }
        }

        // 节点执行按 dagNodeId 归组（保留各次节点 end_time，用于批次覆盖判定）
        Map<Long, List<LocalDateTime>> nodeEndTimesByDagNodeId = new HashMap<>();
        for (NodeExecutionInfo node : nodes) {
            Long dagId = dagIdByExecutionId.get(node.getExecutionId());
            Long dagNodeId = dagId == null ? null : dagNodeIdByKey.get(key(dagId, node.getNodeId()));
            if (dagNodeId == null || node.getEndTime() == null) {
                continue;
            }
            nodeEndTimesByDagNodeId.computeIfAbsent(dagNodeId, k -> new ArrayList<>()).add(node.getEndTime());
        }
        if (nodeEndTimesByDagNodeId.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size() + "，无有效节点");
            return;
        }

        // 4. 查这些 dag_node.id 上绑定的启用质量任务（governance 内部端点；降级为空按「无绑定」本轮跳过）
        AutoTriggerBindingRequest bindingRequest = new AutoTriggerBindingRequest();
        bindingRequest.setDagNodeIds(List.copyOf(nodeEndTimesByDagNodeId.keySet()));
        List<QualityJobBindingDTO> bindings = RemoteCalls.execute("governance.ops.auto-trigger-bindings", () -> {
            Result<List<QualityJobBindingDTO>> result = governanceOpsApi.autoTriggerBindings(bindingRequest);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
        if (bindings.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size() + "，无绑定质量任务");
            return;
        }
        Map<Long, List<Long>> jobIdsByDagNodeId = new HashMap<>();
        Set<Long> jobIds = new HashSet<>();
        for (QualityJobBindingDTO binding : bindings) {
            jobIdsByDagNodeId.computeIfAbsent(binding.getObjectId(), k -> new ArrayList<>()).add(binding.getJobId());
            jobIds.add(binding.getJobId());
        }

        // 5. 逐节点查重：绑定任务在「该节点最晚 end_time - 容忍量」之后已有 AUTO_TRIGGER 批次则视为覆盖，
        //    否则判定漏触发（端点返回去重 jobId 列表；绑定节点数少，逐节点一次远程调用，非热路径）
        Set<Long> missedDagNodeIds = new LinkedHashSet<>();
        for (Map.Entry<Long, List<LocalDateTime>> entry : nodeEndTimesByDagNodeId.entrySet()) {
            List<Long> boundJobIds = jobIdsByDagNodeId.get(entry.getKey());
            if (boundJobIds == null) {
                continue;
            }
            LocalDateTime latestNodeEnd = entry.getValue().stream().max(LocalDateTime::compareTo).orElse(now);
            AutoTriggeredBatchQueryRequest batchQuery = new AutoTriggeredBatchQueryRequest();
            batchQuery.setJobIds(boundJobIds);
            batchQuery.setSince(latestNodeEnd.minusMinutes(BATCH_TOLERANCE_MINUTES)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            List<Long> coveredJobIds = RemoteCalls.execute("governance.ops.auto-triggered-since", () -> {
                Result<List<Long>> result = governanceOpsApi.autoTriggeredJobIdsSince(batchQuery);
                return result == null || result.data() == null ? List.of() : result.data();
            }, List.of());
            if (!new HashSet<>(coveredJobIds).containsAll(boundJobIds)) {
                missedDagNodeIds.add(entry.getKey());
            }
        }
        if (missedDagNodeIds.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size()
                    + ", 绑定任务=" + jobIds.size() + "，无漏触发");
            return;
        }

        // 6. 补发（每轮上限 REISSUE_LIMIT；RemoteCalls 容错，失败下轮窗口内再补）
        List<Long> reissueIds = missedDagNodeIds.stream().limit(REISSUE_LIMIT).toList();
        QualityAutoTriggerBatchRequest request = new QualityAutoTriggerBatchRequest();
        request.setObjectType(OBJECT_TYPE_DAG_NODE);
        request.setObjectIds(reissueIds);
        RemoteCalls.execute("quality.auto-trigger-reconcile",
                () -> governanceObjectApi.qualityAutoTriggerBatch(request));
        logger.info("质量自动触发对账完成: scannedDags={}, scannedNodes={}, boundJobs={}, missed={}, reissued={}",
                dags.size(), nodes.size(), jobIds.size(), missedDagNodeIds.size(), reissueIds.size());
        XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size()
                + ", 漏触发=" + missedDagNodeIds.size() + ", 补发=" + reissueIds.size());
    }

    private static String key(Long dagId, String nodeId) {
        return dagId + ":" + nodeId;
    }
}
