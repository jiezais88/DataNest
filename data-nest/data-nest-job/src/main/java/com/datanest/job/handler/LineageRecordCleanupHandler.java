package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.LineageRecord;
import com.datanest.task.core.mapper.LineageRecordMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 血缘记录定时清理任务。
 * 清理超过保留天数的 lineage_record 记录（含表级血缘与字段级血缘），避免随 DAG 执行次数无限膨胀。
 */
@Component
public class LineageRecordCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(LineageRecordCleanupHandler.class);

    private final LineageRecordMapper lineageRecordMapper;
    private final int retainDays;

    public LineageRecordCleanupHandler(LineageRecordMapper lineageRecordMapper,
                                       @Value("${datanest.job.lineage-cleanup.retain-days:90}") int retainDays) {
        this.lineageRecordMapper = lineageRecordMapper;
        this.retainDays = Math.max(1, retainDays);
    }

    @Transactional
    @XxlJob("lineageRecordCleanupHandler")
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retainDays);
        logger.info("Starting lineage record cleanup, threshold={}, retainDays={}", threshold, retainDays);
        try {
            int rows = lineageRecordMapper.delete(
                    new QueryWrapper<LineageRecord>().lt("created_at", threshold));
            logger.info("Lineage record cleanup completed: rows={}", rows);
            XxlJobHelper.handleSuccess("清理完成: rows=" + rows);
        } catch (Exception e) {
            logger.error("Lineage record cleanup failed", e);
            XxlJobHelper.handleFail("血缘记录清理失败: " + e.getMessage());
        }
    }
}
