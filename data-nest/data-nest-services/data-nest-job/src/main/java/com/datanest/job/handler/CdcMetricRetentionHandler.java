package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcOpsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CDC 分钟指标历史保留期清理（2026-08-17：原 realtime 侧 @Scheduled 本地调度迁至 job 统一调度）。
 * <p>
 * 每天 03:40 触发 realtime 删除早于 now - retention-days 的分钟快照，防指标历史表无限膨胀。
 * 保留天数配置键 datanest.realtime.metric.retention-days 仍在 realtime 侧（清理逻辑所在）。
 * fail-open：realtime 不可达本轮跳过，下轮调度再来。
 */
@Component
public class CdcMetricRetentionHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(CdcMetricRetentionHandler.class);

    private final CdcOpsApi cdcOpsApi;

    public CdcMetricRetentionHandler(CdcOpsApi cdcOpsApi) {
        this.cdcOpsApi = cdcOpsApi;
    }

    @Override
    public String getName() {
        return "cdcMetricRetentionHandler";
    }

    @Override
    public void execute(String param) {
        Boolean ok = RemoteCalls.execute("realtime.cdc-metrics.cleanup", () -> {
            Result<Void> result = cdcOpsApi.cleanupMetrics();
            return result == null ? null : true;
        }, null);
        if (ok == null) {
            logger.warn("CDC 指标历史清理触发失败: realtime 不可达，本轮跳过");
        }
    }
}
