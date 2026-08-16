package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DAG 定时调度触发器（Sprint 11 F3 方案 A）。
 * <p>
 * 每个启用调度的 CRON DAG 在 job 侧注册一个独立 cron job，processorInfo 路由到本 handler，
 * jobParams 存 dagId。cron 到点后：
 * <pre>
 *  1. 解析 param=dagId
 *  2. 经 Feign 调 engineering /internal/dag/scheduled-trigger?dagId=
 *  3. engineering 做队列容量判定：满 → 建 WAITING 入等待池（由 QueueDispatcherHandler 补触发）；
 *     空 → 建 RUNNING 直接执行（预建节点 + runWorkflow）
 * </pre>
 * <p>
 * 失败/熔断降级：本轮跳过（下轮 cron 再触发），不阻塞调度器；同 DAG 已有 WAITING/RUNNING 时
 * engineering 抛 DAG_ALREADY_RUNNING，此处按「本次已执行/排队中」静默吞掉（定时调度不允许叠跑）。
 */
@Component
public class DagScheduledTriggerHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DagScheduledTriggerHandler.class);

    private final EngineeringDagExecutionApi dagExecutionApi;

    public DagScheduledTriggerHandler(EngineeringDagExecutionApi dagExecutionApi) {
        this.dagExecutionApi = dagExecutionApi;
    }

    @Override
    public String getName() {
        return "dagScheduledTriggerHandler";
    }

    @Override
    public void execute(String param) {
        final Long dagId;
        try {
            if (param == null || param.isBlank()) {
                logger.warn("DAG 定时触发跳过：jobParams 为空");
                return;
            }
            dagId = Long.parseLong(param.trim());
        } catch (NumberFormatException e) {
            logger.warn("DAG 定时触发跳过：jobParams 非法, param={}", param);
            return;
        }

        Long executionId = RemoteCalls.execute("engineering.dag.scheduled-trigger", () -> {
            Result<Long> result = dagExecutionApi.scheduledTrigger(dagId);
            return result == null ? null : result.data();
        }, null);

        if (executionId != null) {
            logger.info("DAG 定时触发成功: dagId={}, executionId={}", dagId, executionId);
        } else {
            logger.info("DAG 定时触发跳过（已在运行/排队中或熔断降级）: dagId={}", dagId);
        }
    }
}
