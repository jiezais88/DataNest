package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.AlertHistory;
import com.datanest.task.core.mapper.AlertHistoryMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 告警发送历史定时清理任务。
 * 清理超过保留天数的 alert_history 记录，避免高频告警场景下数据无限膨胀。
 */
@Component
public class AlertHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(AlertHistoryCleanupHandler.class);

    private final AlertHistoryMapper alertHistoryMapper;
    private final int retainDays;

    public AlertHistoryCleanupHandler(AlertHistoryMapper alertHistoryMapper,
                                      @Value("${datanest.job.alert-history-cleanup.retain-days:90}") int retainDays) {
        this.alertHistoryMapper = alertHistoryMapper;
        this.retainDays = Math.max(1, retainDays);
    }

    @Transactional
    @XxlJob("alertHistoryCleanupHandler")
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retainDays);
        logger.info("Starting alert history cleanup, threshold={}, retainDays={}", threshold, retainDays);
        try {
            int rows = alertHistoryMapper.delete(
                    new QueryWrapper<AlertHistory>().lt("sent_at", threshold));
            logger.info("Alert history cleanup completed: rows={}", rows);
            XxlJobHelper.handleSuccess("清理完成: rows=" + rows);
        } catch (Exception e) {
            logger.error("Alert history cleanup failed", e);
            XxlJobHelper.handleFail("告警历史清理失败: " + e.getMessage());
        }
    }
}
