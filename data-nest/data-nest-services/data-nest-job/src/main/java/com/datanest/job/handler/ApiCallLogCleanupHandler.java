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
 * API 调用明细定时清理任务（Sprint 10 F3 补全）。
 * <p>
 * 清理超过保留天数的 api_call_log（默认 30 天）。
 * 清理逻辑下沉 data-service（{@code POST /data-service/internal/api-call-log/cleanup}），
 * 本 handler 只负责调度触发（本地禁止 @Scheduled，统一 PowerJob cron）。
 * RemoteCalls 容错：数据服务不可用本轮跳过，下轮调度再来。
 */
@Component
public class ApiCallLogCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiCallLogCleanupHandler.class);

    private final DataServiceOpsApi dataServiceOpsApi;
    private final int retainDays;

    public ApiCallLogCleanupHandler(DataServiceOpsApi dataServiceOpsApi,
                                    @Value("${datanest.job.api-call-log-cleanup.retain-days:30}") int retainDays) {
        this.dataServiceOpsApi = dataServiceOpsApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "apiCallLogCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting API call log cleanup, retainDays={}", retainDays);
        CleanupRequest request = new CleanupRequest();
        request.setRetainDays(retainDays);
        Integer rows = RemoteCalls.execute("data-service.ops.api-call-log-cleanup", () -> {
            Result<Integer> result = dataServiceOpsApi.cleanupApiCallLog(request);
            return result == null ? null : result.data();
        }, null);
        if (rows == null) {
            throw new IllegalStateException("API 调用明细清理失败: data-service 服务不可用，本轮跳过");
        }
        logger.info("API call log cleanup completed: rows={}", rows);
    }
}
