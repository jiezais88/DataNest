package com.datanest.job.handler;

import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.DagAlertConfigInfo;
import com.datanest.alert.api.dto.DagNodeTimeoutRequest;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 4：DAG 节点超时告警扫描。
 * 扫描 RUNNING 且超过阈值的节点，经 alert-service 远程触发邮件告警（防重发由 alert-service 保证）。
 * 微服务化改造：告警配置解析经 Feign 获取，超时阈值判断逻辑留在本 handler。
 * 微服务化 3.3：RUNNING 节点扫描（dagId 由服务端 join 带入）与 dag_execution 读取
 * 改经 EngineeringDagExecutionApi 远程获取，RemoteCalls 降级本轮跳过。
 */
@Component
public class DagNodeTimeoutAlertHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagNodeTimeoutAlertHandler.class);
    private static final int BATCH_LIMIT = 100;
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;

    private final EngineeringDagExecutionApi dagExecutionApi;
    private final AlertApi alertApi;

    public DagNodeTimeoutAlertHandler(EngineeringDagExecutionApi dagExecutionApi,
                                      AlertApi alertApi) {
        this.dagExecutionApi = dagExecutionApi;
        this.alertApi = alertApi;
    }

    @Override
    public String getName() {
        return "dagNodeTimeoutAlertHandler";
    }

    @Override
    public void execute(String param) {
        List<NodeExecutionInfo> runningNodes = RemoteCalls.execute("engineering.node-execution.running-with-dag", () -> {
            Result<List<NodeExecutionInfo>> result = dagExecutionApi.runningWithDag(BATCH_LIMIT);
            return result == null || result.data() == null ? List.of() : result.data();
        }, List.of());
        if (runningNodes.isEmpty()) {
            logger.info("DAG 节点超时告警扫描完成: 无运行中节点");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int sent = 0;
        for (NodeExecutionInfo node : runningNodes) {
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
        logger.info("DAG 节点超时告警扫描完成: runningNodes={}, sent={}", runningNodes.size(), sent);
    }

    /** 远程解析生效的 DAG 告警配置（Result 拆信封；无配置或熔断降级返回 null，调用侧跳过本次判断） */
    private DagAlertConfigInfo resolveConfig(Long dagId) {
        Result<DagAlertConfigInfo> result = alertApi.resolveDagAlertConfig(dagId);
        return result == null ? null : result.data();
    }

    /** 节点 DTO → 超时通知请求（executionStartTime 从 dag_execution 反查） */
    private DagNodeTimeoutRequest toTimeoutRequest(NodeExecutionInfo node) {
        DagNodeTimeoutRequest request = new DagNodeTimeoutRequest();
        request.setDagId(node.getDagId());
        request.setExecutionId(node.getExecutionId());
        request.setNodeId(node.getNodeId());
        request.setNodeName(node.getNodeName());
        request.setNodeType(node.getNodeType());
        request.setNodeStartTime(node.getStartTime());
        DagExecutionInfo execution = node.getExecutionId() == null ? null
                : RemoteCalls.execute("engineering.dag-execution.get", () -> {
                    Result<DagExecutionInfo> result = dagExecutionApi.getById(node.getExecutionId());
                    return result == null ? null : result.data();
                }, null);
        request.setExecutionStartTime(execution == null ? null : execution.getStartTime());
        return request;
    }
}
