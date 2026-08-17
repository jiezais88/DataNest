package com.datanest.realtime.service;

import com.datanest.realtime.mapper.CdcMetricMinuteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * CDC 分钟指标历史保留期清理（Sprint 9 F1，T1：保留 30 天可配）。
 * <p>
 * 每日 03:40 删除早于 now - retention-days 的分钟快照；防指标历史表无限膨胀（R2）。
 */
@Service
public class MetricRetentionCleaner {

    private static final Logger logger = LoggerFactory.getLogger(MetricRetentionCleaner.class);

    private final CdcMetricMinuteMapper metricMapper;

    /** 指标历史保留天数（T1 默认 30） */
    @Value("${datanest.realtime.metric.retention-days:30}")
    private Integer retentionDays;

    public MetricRetentionCleaner(CdcMetricMinuteMapper metricMapper) {
        this.metricMapper = metricMapper;
    }

    /**
     * 分钟指标历史清理（原 @Scheduled 本地调度，2026-08-17 迁至 app-job 统一调度，
     * 由 CdcInternalController.cleanupMetrics 端点经 Feign 每天 03:40 触发）。
     */
    public void cleanExpired() {
        try {
            LocalDateTime boundary = LocalDateTime.now().minusDays(retentionDays);
            int deleted = metricMapper.deleteBefore(boundary);
            if (deleted > 0) {
                logger.info("CDC 分钟指标历史清理完成: 删除 {} 行（早于 {}，保留 {} 天）", deleted, boundary, retentionDays);
            }
        } catch (Exception e) {
            logger.error("CDC 分钟指标历史清理失败: error={}", e.getMessage(), e);
        }
    }
}
