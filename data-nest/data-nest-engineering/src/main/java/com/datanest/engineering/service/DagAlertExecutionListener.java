package com.datanest.engineering.service;

import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.service.DagAlertService;
import com.datanest.task.core.service.DagExecutionFinishedListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DAG 执行终态监听器：触发邮件告警。
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
