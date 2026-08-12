package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.dataservice.api.DataServiceOpsApi;
import com.datanest.dataservice.api.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SQL 查询历史定时清理任务（Sprint 10 F1）。
 * <p>
 * 清理超过保留天数的 sql_query_history（默认 30 天，前端查询历史只展示近期）。
 * 清理逻辑下沉 data-service（{@code POST /data-service/internal/sql-history/cleanup}），
 * 本 handler 只负责调度触发（本地禁止 @Scheduled，统一 PowerJob cron）。
 * RemoteCalls 容错：数据服务不可用本轮跳过，下轮调度再来。
 */
@Component
public class SqlHistoryCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(SqlHistoryCleanupHandler.class);

    private final DataServiceOpsApi dataServiceOpsApi;
    private final int retainDays;

    public SqlHistoryCleanupHandler(DataServiceOpsApi dataServiceOpsApi,
                                    @Value("${datanest.job.sql-history-cleanup.retain-days:30}") int retainDays) {
        this.dataServiceOpsApi = dataServiceOpsApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "sqlHistoryCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting SQL query history cleanup, retainDays={}", retainDays);
        CleanupRequest request = new CleanupRequest();
        request.setRetainDays(retainDays);
        Integer rows = RemoteCalls.execute("data-service.ops.sql-history-cleanup", () -> {
            Result<Integer> result = dataServiceOpsApi.cleanupSqlQueryHistory(request);
            return result == null ? null : result.data();
        }, null);
        if (rows == null) {
            throw new IllegalStateException("SQL 查询历史清理失败: data-service 服务不可用，本轮跳过");
        }
        logger.info("SQL query history cleanup completed: rows={}", rows);
    }
}
