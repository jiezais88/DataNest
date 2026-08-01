package com.datanest.job.handler;

import com.datanest.task.core.entity.DagAlertConfig;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.datanest.task.core.service.DagAlertService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 4：DAG 节点超时告警扫描。
 * 扫描 RUNNING 且超过阈值的节点，触发邮件告警（防重发由 DagAlertService 保证）。
 */
@Component
public class DagNodeTimeoutAlertHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeTimeoutAlertHandler.class);
    private static final int BATCH_LIMIT = 100;
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;

    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagAlertService dagAlertService;

    public DagNodeTimeoutAlertHandler(NodeExecutionMapper nodeExecutionMapper,
                                      DagAlertService dagAlertService) {
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagAlertService = dagAlertService;
    }

    @XxlJob("dagNodeTimeoutAlertHandler")
    public void scan() {
        List<NodeExecution> runningNodes = nodeExecutionMapper.selectRunningWithDagId(BATCH_LIMIT);
        if (runningNodes.isEmpty()) {
            XxlJobHelper.handleSuccess("无运行中节点");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int sent = 0;
        for (NodeExecution node : runningNodes) {
            try {
                DagAlertConfig config = dagAlertService.resolveConfig(node.getDagId());
                if (config == null || config.getEnabled() == null || config.getEnabled() != 1) {
                    continue;
                }
                int thresholdMinutes = config.getTimeoutMinutes() == null || config.getTimeoutMinutes() <= 0
                        ? DEFAULT_TIMEOUT_MINUTES : config.getTimeoutMinutes();
                LocalDateTime threshold = now.minusMinutes(thresholdMinutes);
                if (node.getStartTime() != null && node.getStartTime().isBefore(threshold)) {
                    dagAlertService.onNodeTimeout(node, node.getDagId());
                    sent++;
                }
            } catch (Exception e) {
                logger.error("发送节点超时告警失败: executionId={}, nodeId={}",
                        node.getExecutionId(), node.getNodeId(), e);
            }
        }
        XxlJobHelper.handleSuccess("扫描完成: runningNodes=" + runningNodes.size() + ", sent=" + sent);
    }
}
