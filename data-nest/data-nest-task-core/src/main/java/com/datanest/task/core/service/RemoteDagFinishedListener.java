package com.datanest.task.core.service;

import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.DagExecutionInfo;
import com.datanest.alert.api.dto.DagFinishedRequest;
import com.datanest.alert.api.dto.NodeExecutionInfo;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DAG 执行终态监听器：通过 Feign 通知 alert-service。
 * <p>
 * 微服务化改造：替代原进程内的 DAG 告警执行监听器，
 * FAILED/SUCCESS 告警与成功节点的质量任务自动触发均由 alert-service 的
 * /alert/internal/dag-finished 端点内部完成。
 * 远程调用失败仅记 error 日志，不影响 DAG 执行结果（最终一致）。
 */
@Component
public class RemoteDagFinishedListener implements DagExecutionFinishedListener {

    private static final Logger logger = LoggerFactory.getLogger(RemoteDagFinishedListener.class);

    private final AlertApi alertApi;

    public RemoteDagFinishedListener(AlertApi alertApi) {
        this.alertApi = alertApi;
    }

    @Override
    public void onFinished(DagExecution execution, List<NodeExecution> nodes) {
        if (execution == null || nodes == null) {
            return;
        }
        try {
            DagFinishedRequest request = new DagFinishedRequest();
            request.setExecution(toExecutionInfo(execution));
            // NodeExecutionInfo 需要 dagId，实体上没有，从 execution 取
            request.setNodes(nodes.stream().map(n -> toNodeInfo(n, execution.getDagId())).toList());
            alertApi.dagFinished(request);
        } catch (Exception e) {
            logger.error("DAG 终态告警通知失败（不影响 DAG 执行结果）: executionId={}, status={}",
                    execution.getId(), execution.getStatus(), e);
        }
    }

    private DagExecutionInfo toExecutionInfo(DagExecution execution) {
        DagExecutionInfo info = new DagExecutionInfo();
        info.setId(execution.getId());
        info.setDagId(execution.getDagId());
        info.setStatus(execution.getStatus());
        info.setStartTime(execution.getStartTime());
        info.setEndTime(execution.getEndTime());
        return info;
    }

    private NodeExecutionInfo toNodeInfo(NodeExecution node, Long dagId) {
        NodeExecutionInfo info = new NodeExecutionInfo();
        info.setId(node.getId());
        info.setExecutionId(node.getExecutionId());
        info.setDagId(dagId);
        info.setNodeId(node.getNodeId());
        info.setNodeName(node.getNodeName());
        info.setNodeType(node.getNodeType());
        info.setStatus(node.getStatus());
        info.setErrorMessage(node.getErrorMessage());
        info.setStartTime(node.getStartTime());
        info.setEndTime(node.getEndTime());
        return info;
    }
}
