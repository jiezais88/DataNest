package com.datanest.job.handler;

import com.datanest.alert.api.AlertApi;
import com.datanest.common.model.Result;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 告警发送历史定时清理任务。
 * 清理超过保留天数的 alert_history 记录，避免高频告警场景下数据无限膨胀。
 * 微服务化改造：alert_history 归 alert-service 所有，清理经 Feign 远程执行。
 */
@Component
public class AlertHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(AlertHistoryCleanupHandler.class);

    private final AlertApi alertApi;
    private final int retainDays;

    public AlertHistoryCleanupHandler(AlertApi alertApi,
                                      @Value("${datanest.job.alert-history-cleanup.retain-days:90}") int retainDays) {
        this.alertApi = alertApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @XxlJob("alertHistoryCleanupHandler")
    public void cleanup() {
        logger.info("Starting alert history cleanup, retainDays={}", retainDays);
        try {
            Result<Integer> result = alertApi.cleanupHistories(retainDays);
            int rows = result != null && result.data() != null ? result.data() : 0;
            logger.info("Alert history cleanup completed: rows={}", rows);
            XxlJobHelper.handleSuccess("清理完成: rows=" + rows);
        } catch (Exception e) {
            logger.error("Alert history cleanup failed", e);
            XxlJobHelper.handleFail("告警历史清理失败: " + e.getMessage());
        }
    }
}
