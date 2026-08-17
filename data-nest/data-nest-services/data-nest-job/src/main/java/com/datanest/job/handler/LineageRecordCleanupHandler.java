package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceOpsApi;
import com.datanest.governance.api.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 血缘记录定时清理任务。
 * 清理超过保留天数的 lineage_record 记录（含表级血缘与字段级血缘），避免随 DAG 执行次数无限膨胀。
 * <p>
 * 微服务化 4.3：血缘表归治理域，清理逻辑下沉 governance
 * （{@code POST /governance/internal/lineage/cleanup}），本 handler 只负责调度触发。
 * RemoteCalls 容错：governance 不可用本轮跳过，下轮调度再来。
 */
@Component
@RefreshScope
public class LineageRecordCleanupHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(LineageRecordCleanupHandler.class);

    private final GovernanceOpsApi governanceOpsApi;
    private final int retainDays;

    public LineageRecordCleanupHandler(GovernanceOpsApi governanceOpsApi,
                                       @Value("${datanest.job.lineage-cleanup.retain-days:90}") int retainDays) {
        this.governanceOpsApi = governanceOpsApi;
        this.retainDays = Math.max(1, retainDays);
    }

    @Override
    public String getName() {
        return "lineageRecordCleanupHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting lineage record cleanup, retainDays={}", retainDays);
        CleanupRequest request = new CleanupRequest();
        request.setRetainDays(retainDays);
        Integer rows = RemoteCalls.execute("governance.ops.lineage-cleanup", () -> {
            Result<Integer> result = governanceOpsApi.cleanupLineageRecord(request);
            return result == null ? null : result.data();
        }, null);
        if (rows == null) {
            throw new IllegalStateException("血缘记录清理失败: governance 服务不可用，本轮跳过");
        }
        logger.info("Lineage record cleanup completed: rows={}", rows);
    }
}
