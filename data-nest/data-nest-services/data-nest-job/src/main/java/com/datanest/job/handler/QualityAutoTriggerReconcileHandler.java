package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.governance.api.GovernanceObjectApi;
import com.datanest.governance.api.dto.QualityAutoTriggerBatchRequest;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.entity.QualityCheckBatch;
import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.DagNodeMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.datanest.task.core.mapper.QualityCheckBatchMapper;
import com.datanest.task.core.mapper.QualityJobMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final String TRIGGER_TYPE_AUTO = "AUTO_TRIGGER";

    private final DagExecutionMapper dagExecutionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagNodeMapper dagNodeMapper;
    private final QualityJobMapper qualityJobMapper;
    private final QualityCheckBatchMapper qualityCheckBatchMapper;
    private final GovernanceObjectApi governanceObjectApi;

    public QualityAutoTriggerReconcileHandler(DagExecutionMapper dagExecutionMapper,
                                              NodeExecutionMapper nodeExecutionMapper,
                                              DagNodeMapper dagNodeMapper,
                                              QualityJobMapper qualityJobMapper,
                                              QualityCheckBatchMapper qualityCheckBatchMapper,
                                              GovernanceObjectApi governanceObjectApi) {
        this.dagExecutionMapper = dagExecutionMapper;
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagNodeMapper = dagNodeMapper;
        this.qualityJobMapper = qualityJobMapper;
        this.qualityCheckBatchMapper = qualityCheckBatchMapper;
        this.governanceObjectApi = governanceObjectApi;
    }

    @XxlJob("qualityAutoTriggerReconcileHandler")
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        // 1. 扫描窗口内成功的 DAG 执行（SUCCESS，end_time 在最近 2 小时且早于 now-5 分钟）
        List<DagExecution> dags = dagExecutionMapper.selectList(new QueryWrapper<DagExecution>()
                .eq("status", STATUS_SUCCESS)
                .ge("end_time", now.minusHours(WINDOW_HOURS))
                .lt("end_time", now.minusMinutes(SAFE_MARGIN_MINUTES))
                .orderByAsc("end_time")
                .last("LIMIT " + SCAN_LIMIT));
        if (dags.isEmpty()) {
            XxlJobHelper.handleSuccess("无待对账执行");
            return;
        }
        Map<Long, Long> dagIdByExecutionId = new HashMap<>();
        for (DagExecution dag : dags) {
            dagIdByExecutionId.put(dag.getId(), dag.getDagId());
        }

        // 2. 这些执行下 SUCCESS 的节点执行
        List<NodeExecution> nodes = nodeExecutionMapper.selectList(new QueryWrapper<NodeExecution>()
                .in("execution_id", dagIdByExecutionId.keySet())
                .eq("status", STATUS_SUCCESS));
        if (nodes.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + "，无成功节点");
            return;
        }

        // 3. 按 dag_id + node_id 解析 dag_node.id（一次 in 查询，不逐条）
        Set<Long> dagIds = new HashSet<>(dagIdByExecutionId.values());
        Map<String, Long> dagNodeIdByKey = new HashMap<>();
        for (DagNode dagNode : dagNodeMapper.selectList(new QueryWrapper<DagNode>().in("dag_id", dagIds))) {
            dagNodeIdByKey.put(key(dagNode.getDagId(), dagNode.getNodeId()), dagNode.getId());
        }

        // 节点执行按 dagNodeId 归组（保留各次节点 end_time，用于批次覆盖判定）
        Map<Long, List<LocalDateTime>> nodeEndTimesByDagNodeId = new HashMap<>();
        for (NodeExecution node : nodes) {
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

        // 4. 批量查这些 dag_node.id 上绑定的启用质量任务
        List<QualityJob> jobs = qualityJobMapper.selectList(new QueryWrapper<QualityJob>()
                .eq("enabled", 1)
                .eq("auto_trigger_enabled", 1)
                .eq("auto_trigger_object_type", OBJECT_TYPE_DAG_NODE)
                .in("auto_trigger_object_id", nodeEndTimesByDagNodeId.keySet()));
        if (jobs.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size() + "，无绑定质量任务");
            return;
        }
        Map<Long, List<Long>> jobIdsByDagNodeId = new HashMap<>();
        Set<Long> jobIds = new HashSet<>();
        for (QualityJob job : jobs) {
            jobIdsByDagNodeId.computeIfAbsent(job.getAutoTriggerObjectId(), k -> new ArrayList<>()).add(job.getId());
            jobIds.add(job.getId());
        }

        // 5. 批量查窗口内已有的 AUTO_TRIGGER 批次（一次查询，内存判定覆盖）
        LocalDateTime earliestNodeEnd = nodeEndTimesByDagNodeId.values().stream()
                .flatMap(Collection::stream)
                .min(LocalDateTime::compareTo)
                .orElse(now);
        Map<Long, List<LocalDateTime>> batchTimesByJobId = new HashMap<>();
        List<QualityCheckBatch> batches = qualityCheckBatchMapper.selectList(new QueryWrapper<QualityCheckBatch>()
                .select("job_id", "created_at")
                .in("job_id", jobIds)
                .eq("trigger_type", TRIGGER_TYPE_AUTO)
                .ge("created_at", earliestNodeEnd.minusMinutes(BATCH_TOLERANCE_MINUTES)));
        for (QualityCheckBatch batch : batches) {
            batchTimesByJobId.computeIfAbsent(batch.getJobId(), k -> new ArrayList<>()).add(batch.getCreatedAt());
        }

        // 任一绑定任务缺少「节点 end_time - 1 分钟之后」的 AUTO_TRIGGER 批次 → 判定漏触发
        Set<Long> missedDagNodeIds = new LinkedHashSet<>();
        for (Map.Entry<Long, List<LocalDateTime>> entry : nodeEndTimesByDagNodeId.entrySet()) {
            List<Long> boundJobIds = jobIdsByDagNodeId.get(entry.getKey());
            if (boundJobIds == null) {
                continue;
            }
            for (LocalDateTime nodeEndTime : entry.getValue()) {
                LocalDateTime threshold = nodeEndTime.minusMinutes(BATCH_TOLERANCE_MINUTES);
                for (Long jobId : boundJobIds) {
                    boolean covered = batchTimesByJobId.getOrDefault(jobId, List.of()).stream()
                            .anyMatch(createdAt -> !createdAt.isBefore(threshold));
                    if (!covered) {
                        missedDagNodeIds.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        if (missedDagNodeIds.isEmpty()) {
            XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size()
                    + ", 绑定任务=" + jobs.size() + "，无漏触发");
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
                dags.size(), nodes.size(), jobs.size(), missedDagNodeIds.size(), reissueIds.size());
        XxlJobHelper.handleSuccess("扫描执行=" + dags.size() + ", 节点=" + nodes.size()
                + ", 漏触发=" + missedDagNodeIds.size() + ", 补发=" + reissueIds.size());
    }

    private static String key(Long dagId, String nodeId) {
        return dagId + ":" + nodeId;
    }
}
