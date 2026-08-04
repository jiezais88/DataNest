package com.datanest.job.handler;

import com.datanest.task.core.entity.QualityJob;
import com.datanest.task.core.service.QualityJobService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量检查定时扫描 Handler（Sprint 6 配置层）。
 * <p>
 * cron {@code 0 * * * * ?} 每分钟扫描一次。扫描「任务整体启用 + 开定时调度 + 配置了 cron」的质量任务，
 * 用 Spring {@link CronExpression} 判断任务 cron 是否命中当前分钟；命中则记录最近触发时间。
 * <p>
 * 本批仅做「扫描 + 预留入口」：真实执行校验（QualityCheckService.executeJob）下一批接入，此处留 TODO 注释。
 */
@Component
public class QualityCheckHandler {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckHandler.class);

    private final QualityJobService jobService;

    public QualityCheckHandler(QualityJobService jobService) {
        this.jobService = jobService;
    }

    @XxlJob("qualityCheckHandler")
    public void scan() {
        logger.info("Starting quality check schedule scan");
        LocalDateTime now = LocalDateTime.now();
        // 当前分钟整点（用于命中判断）
        LocalDateTime minuteStart = now.withSecond(0).withNano(0);

        List<QualityJob> jobs = jobService.listScheduledEnabled();
        int hitCount = 0;
        for (QualityJob job : jobs) {
            if (!isHitNow(job.getCron(), minuteStart)) {
                continue;
            }
            hitCount++;
            logger.info("Quality job [{}] scheduled hit at {}", job.getName(), minuteStart);
            try {
                // 记录最近触发时间（防重 R6：执行层用 last_trigger_at 去重）
                jobService.touchLastTriggerAt(job.getId());
                // TODO 下一批：调用 QualityCheckService.executeJob(job.getId(), "SCHEDULED") 执行真实校验
            } catch (Exception e) {
                logger.error("Quality job [{}] scheduled handling failed", job.getName(), e);
            }
        }
        logger.info("Quality check schedule scan completed: scanned={}, hit={}", jobs.size(), hitCount);
        XxlJobHelper.handleSuccess("扫描完成: 任务=" + jobs.size() + ", 命中=" + hitCount);
    }

    /**
     * 判断 cron 是否命中当前分钟整点。
     * 采用「求 strictly-after 前一纳秒的下一个触发时刻」是否等于当前分钟整点，兼容秒级 cron。
     */
    private boolean isHitNow(String cron, LocalDateTime minuteStart) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            CronExpression expression = CronExpression.parse(cron.trim());
            LocalDateTime next = expression.next(minuteStart.minusNanos(1));
            return next != null && next.equals(minuteStart);
        } catch (Exception e) {
            logger.warn("Invalid quality job cron expression: {}", cron);
            return false;
        }
    }
}
