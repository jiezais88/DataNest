package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.QualityCheckBatch;
import com.datanest.task.core.entity.QualityCheckDetail;
import com.datanest.task.core.mapper.QualityCheckBatchMapper;
import com.datanest.task.core.mapper.QualityCheckDetailMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量检查历史定时清理任务（Sprint 6 补全）。
 * <p>
 * 清理超过保留天数的 quality_check_batch 及其关联 quality_check_detail，
 * 避免质量检查高频执行（定时/自动触发）导致执行历史无限膨胀。
 * 分批删除（每批固定条数）避免一次性加载过多批次占内存。
 */
@Component
public class QualityCheckHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(QualityCheckHistoryCleanupHandler.class);

    /** 每批删除的批次条数上限，防止单次事务过大 */
    private static final int BATCH_LIMIT = 500;

    private final QualityCheckBatchMapper batchMapper;
    private final QualityCheckDetailMapper detailMapper;
    private final int retainDays;

    public QualityCheckHistoryCleanupHandler(QualityCheckBatchMapper batchMapper,
                                             QualityCheckDetailMapper detailMapper,
                                             @Value("${datanest.job.quality-check-cleanup.retain-days:30}") int retainDays) {
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
        this.retainDays = Math.max(1, retainDays);
    }

    @Transactional
    @XxlJob("qualityCheckHistoryCleanupHandler")
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retainDays);
        logger.info("Starting quality check history cleanup, threshold={}, retainDays={}", threshold, retainDays);
        long totalBatches = 0;
        long totalDetails = 0;
        try {
            while (true) {
                // 每批取超期批次 ID（按 id 升序，取前 BATCH_LIMIT 条）
                List<QualityCheckBatch> expired = batchMapper.selectList(
                        new QueryWrapper<QualityCheckBatch>()
                                .select("id")
                                .lt("started_at", threshold)
                                .orderByAsc("id")
                                .last("LIMIT " + BATCH_LIMIT));
                if (expired.isEmpty()) {
                    break;
                }
                List<Long> batchIds = expired.stream().map(QualityCheckBatch::getId).toList();
                // 级联删除明细（按 batch_id）
                int details = detailMapper.delete(
                        new QueryWrapper<QualityCheckDetail>().in("batch_id", batchIds));
                // 删除批次
                int batches = batchMapper.deleteByIds(batchIds);
                totalBatches += batches;
                totalDetails += details;
                logger.info("Quality check history cleanup batch: batches={}, details={}", batches, details);
                // 若本轮删除数小于批量上限，说明已删完，可提前退出（避免多查一次空）
                if (expired.size() < BATCH_LIMIT) {
                    break;
                }
            }
            logger.info("Quality check history cleanup completed: totalBatches={}, totalDetails={}",
                    totalBatches, totalDetails);
            XxlJobHelper.handleSuccess("清理完成: 批次=" + totalBatches + ", 明细=" + totalDetails);
        } catch (Exception e) {
            logger.error("Quality check history cleanup failed", e);
            XxlJobHelper.handleFail("质量检查历史清理失败: " + e.getMessage());
        }
    }
}
