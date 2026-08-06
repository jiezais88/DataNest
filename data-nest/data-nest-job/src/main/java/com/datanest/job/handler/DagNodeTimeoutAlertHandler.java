package com.datanest.job.handler;

import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.DagAlertConfigInfo;
import com.datanest.alert.api.dto.DagNodeTimeoutRequest;
import com.datanest.common.model.Result;
import com.datanest.task.core.entity.DagExecution;
import com.datanest.task.core.entity.NodeExecution;
import com.datanest.task.core.mapper.DagExecutionMapper;
import com.datanest.task.core.mapper.NodeExecutionMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 4：DAG 节点超时告警扫描。
 * 扫描 RUNNING 且超过阈值的节点，经 alert-service 远程触发邮件告警（防重发由 alert-service 保证）。
 * 微服务化改造：告警配置解析经 Feign 获取，超时阈值判断逻辑留在本 handler。
 */
@Component
public class DagNodeTimeoutAlertHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeTimeoutAlertHandler.class);
    private static final int BATCH_LIMIT = 100;
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;

    private final NodeExecutionMapper nodeExecutionMapper;
    private final DagExecutionMapper dagExecutionMapper;
    private final AlertApi alertApi;

    public DagNodeTimeoutAlertHandler(NodeExecutionMapper nodeExecutionMapper,
                                      DagExecutionMapper dagExecutionMapper,
                                      AlertApi alertApi) {
        this.nodeExecutionMapper = nodeExecutionMapper;
        this.dagExecutionMapper = dagExecutionMapper;
        this.alertApi = alertApi;
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
                DagAlertConfigInfo config = resolveConfig(node.getDagId());
                if (config == null || config.getEnabled() == null || config.getEnabled() != 1) {
                    continue;
                }
                int thresholdMinutes = config.getTimeoutMinutes() == null || config.getTimeoutMinutes() <= 0
                        ? DEFAULT_TIMEOUT_MINUTES : config.getTimeoutMinutes();
                LocalDateTime threshold = now.minusMinutes(thresholdMinutes);
                if (node.getStartTime() != null && node.getStartTime().isBefore(threshold)) {
                    alertApi.dagNodeTimeout(toTimeoutRequest(node));
                    sent++;
                }
            } catch (Exception e) {
                logger.error("发送节点超时告警失败: executionId={}, nodeId={}",
                        node.getExecutionId(), node.getNodeId(), e);
            }
        }
        XxlJobHelper.handleSuccess("扫描完成: runningNodes=" + runningNodes.size() + ", sent=" + sent);
    }

    /** 远程解析生效的 DAG 告警配置（Result 拆信封；无配置返回 null） */
    private DagAlertConfigInfo resolveConfig(Long dagId) {
        Result<DagAlertConfigInfo> result = alertApi.resolveDagAlertConfig(dagId);
        return result == null ? null : result.data();
    }

    /** 节点实体 → 超时通知请求（executionStartTime 从 dag_execution 反查） */
    private DagNodeTimeoutRequest toTimeoutRequest(NodeExecution node) {
        DagNodeTimeoutRequest request = new DagNodeTimeoutRequest();
        request.setDagId(node.getDagId());
        request.setExecutionId(node.getExecutionId());
        request.setNodeId(node.getNodeId());
        request.setNodeName(node.getNodeName());
        request.setNodeType(node.getNodeType());
        request.setNodeStartTime(node.getStartTime());
        DagExecution execution = node.getExecutionId() == null ? null
                : dagExecutionMapper.selectById(node.getExecutionId());
        request.setExecutionStartTime(execution == null ? null : execution.getStartTime());
        return request;
    }
}
