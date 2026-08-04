package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.DagNode;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DAG 执行终态监听器：触发邮件告警 + 质量任务自动触发。
 * Sprint 4 下沉到 task-core，供 engineering / worker / job 共用。
 * Sprint 8：DAG 节点成功后触发绑定该节点（dag_node.id）的质量任务自动检查。
 */
@Component
public class DagAlertExecutionListener implements DagExecutionFinishedListener {

    private static final Logger logger = LoggerFactory.getLogger(DagAlertExecutionListener.class);

    private final DagAlertService dagAlertService;
    private final QualityAutoTriggerService qualityAutoTriggerService;
    private final DagNodeMapper dagNodeMapper;

    public DagAlertExecutionListener(DagAlertService dagAlertService,
                                     QualityAutoTriggerService qualityAutoTriggerService,
                                     DagNodeMapper dagNodeMapper) {
        this.dagAlertService = dagAlertService;
        this.qualityAutoTriggerService = qualityAutoTriggerService;
        this.dagNodeMapper = dagNodeMapper;
    }

    @Override
    public void onFinished(DagExecution execution, List<NodeExecution> nodes) {
        if (execution == null || nodes == null) {
            return;
        }
        String status = execution.getStatus();
        if ("FAILED".equalsIgnoreCase(status)) {
            List<NodeExecution> failedNodes = nodes.stream()
                    .filter(n -> "FAILED".equalsIgnoreCase(n.getStatus())
                            || "TERMINATED".equalsIgnoreCase(n.getStatus()))
                    .toList();
            dagAlertService.onDagFailed(execution, failedNodes);
        } else if ("SUCCESS".equalsIgnoreCase(status)) {
            dagAlertService.onDagSuccess(execution);
            triggerQualityOnSuccessNodes(execution, nodes);
        }
    }

    /**
     * DAG 成功后，对每个成功节点反查 dag_node.id 并触发绑定该节点的质量任务自动检查。
     * 触发失败不影响 DAG 执行结果。
     */
    private void triggerQualityOnSuccessNodes(DagExecution execution, List<NodeExecution> nodes) {
        Long dagId = execution.getDagId();
        if (dagId == null) {
            return;
        }
        for (NodeExecution node : nodes) {
            if (!"SUCCESS".equalsIgnoreCase(node.getStatus()) || node.getNodeId() == null) {
                continue;
            }
            try {
                DagNode dagNode = dagNodeMapper.selectOne(new QueryWrapper<DagNode>()
                        .eq("dag_id", dagId).eq("node_id", node.getNodeId()).last("LIMIT 1"));
                if (dagNode == null) {
                    continue;
                }
                qualityAutoTriggerService.triggerOnSuccess(
                        QualityAutoTriggerService.OBJECT_TYPE_DAG_NODE, dagNode.getId());
            } catch (Exception e) {
                logger.error("质量任务自动触发失败（不影响 DAG 结果）: dagId={}, nodeId={}",
                        dagId, node.getNodeId(), e);
            }
        }
    }
}
