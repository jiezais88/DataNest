package com.datanest.governance.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.governance.api.dto.QualityJobBindingDTO;
import com.datanest.governance.entity.CollectChangeDetail;
import com.datanest.governance.entity.CollectExecutionLog;
import com.datanest.governance.entity.CollectHistory;
import com.datanest.governance.entity.LineageRecord;
import com.datanest.governance.entity.QualityCheckBatch;
import com.datanest.governance.entity.QualityCheckDetail;
import com.datanest.governance.entity.QualityJob;
import com.datanest.governance.mapper.CollectChangeDetailMapper;
import com.datanest.governance.mapper.CollectExecutionLogMapper;
import com.datanest.governance.mapper.CollectHistoryMapper;
import com.datanest.governance.mapper.LineageRecordMapper;
import com.datanest.governance.mapper.QualityCheckBatchMapper;
import com.datanest.governance.mapper.QualityCheckDetailMapper;
import com.datanest.governance.mapper.QualityJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 治理域低频运维内部逻辑（实现 governance-api 的 GovernanceOpsApi 契约）。
 * <p>
 * 微服务化 4.1：采集历史/质量检查历史/血缘记录定时清理由 data-nest-job 的
 * CollectHistoryCleanupHandler / QualityCheckHistoryCleanupHandler / LineageRecordCleanupHandler
 * 下沉至本服务；质量自动触发对账（QualityAutoTriggerReconcileHandler）的治理表查询同步下沉。
 */
@Service
public class GovernanceOpsService {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceOpsService.class);

    /** 采集历史默认保留天数（对齐原 CollectHistoryCleanupHandler 固定 30 天） */
    private static final int COLLECT_HISTORY_DEFAULT_RETAIN_DAYS = 30;
    /** 质量检查历史默认保留天数（对齐原 datanest.job.quality-check-cleanup.retain-days 默认 30） */
    private static final int QUALITY_CHECK_DEFAULT_RETAIN_DAYS = 30;
    /** 血缘记录默认保留天数（对齐原 datanest.job.lineage-cleanup.retain-days 默认 90） */
    private static final int LINEAGE_DEFAULT_RETAIN_DAYS = 90;
    /** 每批删除的批次条数上限，防止单次事务过大 */
    private static final int BATCH_LIMIT = 500;

    /** 自动触发对象类型：DAG 节点（与 quality_job.auto_trigger_object_type 对应） */
    private static final String OBJECT_TYPE_DAG_NODE = "DAG_NODE";
    /** 自动触发批次触发方式（与 quality_check_batch.trigger_type 对应） */
    private static final String TRIGGER_TYPE_AUTO = "AUTO_TRIGGER";

    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper collectExecutionLogMapper;
    private final CollectChangeDetailMapper collectChangeDetailMapper;
    private final QualityCheckBatchMapper qualityCheckBatchMapper;
    private final QualityCheckDetailMapper qualityCheckDetailMapper;
    private final LineageRecordMapper lineageRecordMapper;
    private final QualityJobMapper qualityJobMapper;

    public GovernanceOpsService(CollectHistoryMapper collectHistoryMapper,
                                CollectExecutionLogMapper collectExecutionLogMapper,
                                CollectChangeDetailMapper collectChangeDetailMapper,
                                QualityCheckBatchMapper qualityCheckBatchMapper,
                                QualityCheckDetailMapper qualityCheckDetailMapper,
                                LineageRecordMapper lineageRecordMapper,
                                QualityJobMapper qualityJobMapper) {
        this.collectHistoryMapper = collectHistoryMapper;
        this.collectExecutionLogMapper = collectExecutionLogMapper;
        this.collectChangeDetailMapper = collectChangeDetailMapper;
        this.qualityCheckBatchMapper = qualityCheckBatchMapper;
        this.qualityCheckDetailMapper = qualityCheckDetailMapper;
        this.lineageRecordMapper = lineageRecordMapper;
        this.qualityJobMapper = qualityJobMapper;
    }

    /**
     * 清理超过保留天数的采集任务历史（对齐原 CollectHistoryCleanupHandler.cleanup）：
     * 先按 created_at 查待删历史 ID，再按 history_id 级联删变更明细/执行日志，最后删历史。
     *
     * @return 三张表删除总条数（detail + log + history）
     */
    @Transactional
    public int cleanupCollectHistory(Integer retainDays) {
        int days = Math.max(1, retainDays == null ? COLLECT_HISTORY_DEFAULT_RETAIN_DAYS : retainDays);
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        logger.info("Starting collect history cleanup, threshold={}", threshold);
        // 先查询待删除的历史记录 ID
        List<Long> historyIds = collectHistoryMapper.selectList(
                        new QueryWrapper<CollectHistory>().lt("created_at", threshold).select("id"))
                .stream()
                .map(CollectHistory::getId)
                .toList();

        int detailRows = 0;
        int logRows = 0;
        int historyRows = 0;
        if (!historyIds.isEmpty()) {
            detailRows = collectChangeDetailMapper.delete(
                    new QueryWrapper<CollectChangeDetail>().in("history_id", historyIds));
            logRows = collectExecutionLogMapper.delete(
                    new QueryWrapper<CollectExecutionLog>().in("history_id", historyIds));
            historyRows = collectHistoryMapper.delete(
                    new QueryWrapper<CollectHistory>().in("id", historyIds));
        }

        logger.info("Collect history cleanup completed: historyRows={}, logRows={}, detailRows={}",
                historyRows, logRows, detailRows);
        return detailRows + logRows + historyRows;
    }

    /**
     * 清理超过保留天数的质量检查历史（对齐原 QualityCheckHistoryCleanupHandler.cleanup）：
     * 按 started_at 超期分批（每批 500）删除 quality_check_batch 并级联 quality_check_detail，
     * 避免高频执行历史无限膨胀且单次事务过大。
     *
     * @return 批次 + 明细删除总条数
     */
    @Transactional
    public int cleanupQualityCheckHistory(Integer retainDays) {
        int days = Math.max(1, retainDays == null ? QUALITY_CHECK_DEFAULT_RETAIN_DAYS : retainDays);
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        logger.info("Starting quality check history cleanup, threshold={}, retainDays={}", threshold, days);
        long totalBatches = 0;
        long totalDetails = 0;
        while (true) {
            // 每批取超期批次 ID（按 id 升序，取前 BATCH_LIMIT 条）
            List<QualityCheckBatch> expired = qualityCheckBatchMapper.selectList(
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
            int details = qualityCheckDetailMapper.delete(
                    new QueryWrapper<QualityCheckDetail>().in("batch_id", batchIds));
            // 删除批次
            int batches = qualityCheckBatchMapper.deleteByIds(batchIds);
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
        return (int) (totalBatches + totalDetails);
    }

    /**
     * 清理超过保留天数的血缘记录（对齐原 LineageRecordCleanupHandler.cleanup）：
     * 含表级血缘与字段级血缘，按 created_at 一次性删除。
     *
     * @return 删除条数
     */
    @Transactional
    public int cleanupLineageRecord(Integer retainDays) {
        int days = Math.max(1, retainDays == null ? LINEAGE_DEFAULT_RETAIN_DAYS : retainDays);
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        logger.info("Starting lineage record cleanup, threshold={}, retainDays={}", threshold, days);
        int rows = lineageRecordMapper.delete(
                new QueryWrapper<LineageRecord>().lt("created_at", threshold));
        logger.info("Lineage record cleanup completed: rows={}", rows);
        return rows;
    }

    /**
     * 查询 DAG 节点上绑定的启用质量任务（对齐 QualityAutoTriggerReconcileHandler 第 4 步）：
     * enabled=1 且 auto_trigger_enabled=1 且 auto_trigger_object_type='DAG_NODE' 且 object_id 在入参内。
     */
    public List<QualityJobBindingDTO> autoTriggerBindings(List<Long> dagNodeIds) {
        if (dagNodeIds == null || dagNodeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = dagNodeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<QualityJob> jobs = qualityJobMapper.selectList(new QueryWrapper<QualityJob>()
                .eq("enabled", 1)
                .eq("auto_trigger_enabled", 1)
                .eq("auto_trigger_object_type", OBJECT_TYPE_DAG_NODE)
                .in("auto_trigger_object_id", ids));
        return jobs.stream().map(job -> {
            QualityJobBindingDTO dto = new QualityJobBindingDTO();
            dto.setJobId(job.getId());
            dto.setJobName(job.getName());
            dto.setObjectId(job.getAutoTriggerObjectId());
            return dto;
        }).toList();
    }

    /**
     * 查询指定质量任务自某时间点起已有的 AUTO_TRIGGER 批次（对齐 QualityAutoTriggerReconcileHandler
     * 第 5 步的批次查重条件），返回去重后的 jobId 列表。
     *
     * @param since ISO_LOCAL_DATE_TIME 字符串；为空白时不加时间下界
     */
    public List<Long> autoTriggeredJobIdsSince(List<Long> jobIds, String since) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = jobIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<QualityCheckBatch> wrapper = new QueryWrapper<QualityCheckBatch>()
                .select("job_id")
                .in("job_id", ids)
                .eq("trigger_type", TRIGGER_TYPE_AUTO);
        if (since != null && !since.isBlank()) {
            wrapper.ge("created_at", LocalDateTime.parse(since.trim()));
        }
        return qualityCheckBatchMapper.selectList(wrapper).stream()
                .map(QualityCheckBatch::getJobId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
