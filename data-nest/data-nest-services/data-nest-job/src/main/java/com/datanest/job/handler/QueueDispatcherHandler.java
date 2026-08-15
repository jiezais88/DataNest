package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringDagExecutionApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 执行队列调度器（Sprint 11 F3 任务资源队列）。
 * <p>
 * 每 5s 轮询（PRD B6 允许秒级延迟）：调 engineering 的队列调度端点，
 * 对有空位的队列取 WAITING 实例（priority DESC, created_at ASC）逐个补触发（QU-6 同队列并发满时高优先先执行）。
 * 同时做对账兜底（技术文档 D-4）：超时 WAITING 强制收尾，job 重启后自动恢复调度，避免等待池停滞。
 * <p>
 * 实际调度/补偿/乐观锁防并发逻辑全在 engineering 侧 {@code QueueDispatchService}，
 * 本 handler 只负责按间隔调用（RemoteCalls 降级本轮跳过，下轮再来）。
 */
@Component
public class QueueDispatcherHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(QueueDispatcherHandler.class);

    private final EngineeringDagExecutionApi dagExecutionApi;

    /** 等待超时收尾阈值（分钟） */
    @Value("${datanest.queue.wait-timeout-minutes:60}")
    private int waitTimeoutMinutes;

    public QueueDispatcherHandler(EngineeringDagExecutionApi dagExecutionApi) {
        this.dagExecutionApi = dagExecutionApi;
    }

    @Override
    public String getName() {
        return "queueDispatcherHandler";
    }

    @Override
    public void execute(String param) {
        try {
            // 1. 常规调度一轮（触发等待池实例直到队列满）
            int triggered = RemoteCalls.execute("engineering.queue.dispatch", () -> {
                Result<Integer> result = dagExecutionApi.dispatchOnce();
                return result == null || result.data() == null ? 0 : result.data();
            }, 0);

            // 2. 对账兜底：超时 WAITING 强制收尾（每 12 轮做一次，避免每次都对账增加无谓开销）
            int reaped = 0;
            if (param == null || param.isBlank() || "reap".equalsIgnoreCase(param.trim())) {
                reaped = RemoteCalls.execute("engineering.queue.reap-stuck-waiting", () -> {
                    Result<Integer> result = dagExecutionApi.reapStuckWaiting(waitTimeoutMinutes);
                    return result == null || result.data() == null ? 0 : result.data();
                }, 0);
            }

            if (triggered > 0 || reaped > 0) {
                logger.info("执行队列调度轮次完成: 触发 {} 个, 对账收尾 {} 个", triggered, reaped);
            }
        } catch (Exception e) {
            logger.error("执行队列调度失败", e);
            throw new IllegalStateException("执行队列调度失败: " + e.getMessage(), e);
        }
    }
}
