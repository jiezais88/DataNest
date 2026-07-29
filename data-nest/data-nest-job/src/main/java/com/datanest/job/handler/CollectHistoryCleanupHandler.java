package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.CollectChangeDetail;
import com.datanest.task.core.entity.CollectExecutionLog;
import com.datanest.task.core.entity.CollectHistory;
import com.datanest.task.core.mapper.CollectChangeDetailMapper;
import com.datanest.task.core.mapper.CollectExecutionLogMapper;
import com.datanest.task.core.mapper.CollectHistoryMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时清理超过 30 天的采集任务历史、执行日志与变更明细。
 */
@Component
public class CollectHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(CollectHistoryCleanupHandler.class);
    private static final int RETENTION_DAYS = 30;

    private final CollectHistoryMapper collectHistoryMapper;
    private final CollectExecutionLogMapper collectExecutionLogMapper;
    private final CollectChangeDetailMapper collectChangeDetailMapper;

    public CollectHistoryCleanupHandler(CollectHistoryMapper collectHistoryMapper,
                                        CollectExecutionLogMapper collectExecutionLogMapper,
                                        CollectChangeDetailMapper collectChangeDetailMapper) {
        this.collectHistoryMapper = collectHistoryMapper;
        this.collectExecutionLogMapper = collectExecutionLogMapper;
        this.collectChangeDetailMapper = collectChangeDetailMapper;
    }

    @Transactional
    @XxlJob("collectHistoryCleanupHandler")
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        logger.info("Starting collect history cleanup, threshold={}", threshold);
        try {
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
            XxlJobHelper.handleSuccess("清理完成: history=" + historyRows + ", log=" + logRows + ", detail=" + detailRows);
        } catch (Exception e) {
            logger.error("Collect history cleanup failed", e);
            XxlJobHelper.handleFail("采集历史清理失败: " + e.getMessage());
        }
    }
}
