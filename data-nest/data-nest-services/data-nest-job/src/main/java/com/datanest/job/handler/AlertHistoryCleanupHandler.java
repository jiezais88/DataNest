package com.datanest.job.handler;

import com.datanest.alert.api.AlertApi;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 告警发送历史定时清理任务。
 * 清理超过保留天数的 alert_history 记录，避免高频告警场景下数据无限膨胀。
 * 微服务化改造：alert_history 归 alert-service 所有，清理经 Feign 远程执行。
 */
@Component
@RefreshScope
public class AlertHistoryCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(AlertHistoryCleanupHandler.class);

    private final AlertApi alertApi;
    private final int retainDays;

    public AlertHistoryCleanupHandler(AlertApi alertApi,
                                      @Value("${datanest.job.alert-history-cleanup.retain-days:90}") int retainDays) {
        this.alertApi = alertApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "alertHistoryCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting alert history cleanup, retainDays={}", retainDays);
        // RemoteCalls 统一降级：熔断 fallback 之外的异常（如序列化错）返回 -1，按失败上报 PowerJob
        Integer rows = RemoteCalls.execute("alert.cleanup", () -> {
            Result<Integer> result = alertApi.cleanupHistories(retainDays);
            return result != null && result.data() != null ? result.data() : 0;
        }, -1);
        if (rows < 0) {
            throw new IllegalStateException("告警历史清理失败，详见日志");
        }
        logger.info("Alert history cleanup completed: rows={}", rows);
    }
}
