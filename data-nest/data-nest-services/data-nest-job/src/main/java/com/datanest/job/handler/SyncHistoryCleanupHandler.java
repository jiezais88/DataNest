package com.datanest.job.handler;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.EngineeringSyncJobApi;
import com.datanest.engineering.api.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 定时清理超过 30 天的同步任务历史与日志。
 * 微服务化 3.2：清理逻辑已下沉 engineering（单事务删 sync_job_history + sync_job_log），
 * 本 handler 只负责调度触发 cleanup 端点。
 */
@Component
public class SyncHistoryCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(SyncHistoryCleanupHandler.class);
    private static final int RETENTION_DAYS = 30;

    private final EngineeringSyncJobApi syncJobApi;

    public SyncHistoryCleanupHandler(EngineeringSyncJobApi syncJobApi) {
        this.syncJobApi = syncJobApi;
    }

    @Override
    public String getName() {
        return "syncHistoryCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting sync history cleanup, retentionDays={}", RETENTION_DAYS);
        try {
            CleanupRequest request = new CleanupRequest();
            request.setRetainDays(RETENTION_DAYS);
            Result<Integer> result = syncJobApi.cleanupHistories(request);
            int deleted = result == null || result.data() == null ? 0 : result.data();
            logger.info("Sync history cleanup completed: deletedRows={}", deleted);
        } catch (Exception e) {
            logger.error("Sync history cleanup failed", e);
            throw new IllegalStateException("同步历史清理失败: " + e.getMessage(), e);
        }
    }
}
