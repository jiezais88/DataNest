package com.datanest.task.core.service;

import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DAG 执行终态监听器：触发邮件告警。
 * Sprint 4 下沉到 task-core，供 engineering / worker / job 共用。
 */
@Component
public class DagAlertExecutionListener implements DagExecutionFinishedListener {

    private final DagAlertService dagAlertService;

    public DagAlertExecutionListener(DagAlertService dagAlertService) {
        this.dagAlertService = dagAlertService;
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
        }
    }
}
