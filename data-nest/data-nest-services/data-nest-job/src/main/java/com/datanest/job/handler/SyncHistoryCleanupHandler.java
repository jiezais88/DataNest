package com.datanest.job.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.task.core.entity.SyncJobHistory;
import com.datanest.task.core.entity.SyncJobLog;
import com.datanest.task.core.mapper.SyncJobHistoryMapper;
import com.datanest.task.core.mapper.SyncJobLogMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定时清理超过 30 天的同步任务历史与日志。
 */
@Component
public class SyncHistoryCleanupHandler {

    private static final Logger logger = LoggerFactory.getLogger(SyncHistoryCleanupHandler.class);
    private static final int RETENTION_DAYS = 30;

    private final SyncJobHistoryMapper syncJobHistoryMapper;
    private final SyncJobLogMapper syncJobLogMapper;

    public SyncHistoryCleanupHandler(SyncJobHistoryMapper syncJobHistoryMapper, SyncJobLogMapper syncJobLogMapper) {
        this.syncJobHistoryMapper = syncJobHistoryMapper;
        this.syncJobLogMapper = syncJobLogMapper;
    }

    @Transactional
    @XxlJob("syncHistoryCleanupHandler")
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        logger.info("Starting sync history cleanup, threshold={}", threshold);
        try {
            int historyRows = syncJobHistoryMapper.delete(
                    new QueryWrapper<SyncJobHistory>().lt("created_at", threshold));
            int logRows = syncJobLogMapper.delete(
                    new QueryWrapper<SyncJobLog>().lt("created_at", threshold));
            logger.info("Sync history cleanup completed: historyRows={}, logRows={}", historyRows, logRows);
            XxlJobHelper.handleSuccess("清理完成: history=" + historyRows + ", log=" + logRows);
        } catch (Exception e) {
            logger.error("Sync history cleanup failed", e);
            XxlJobHelper.handleFail("同步历史清理失败: " + e.getMessage());
        }
    }
}
