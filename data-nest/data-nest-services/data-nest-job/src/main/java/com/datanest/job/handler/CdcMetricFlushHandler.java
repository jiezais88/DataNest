package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcOpsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CDC 分钟级指标落库触发（2026-08-17：原 realtime 侧 @Scheduled 本地调度迁至 job 统一调度）。
 * <p>
 * 每 60s 触发一次 realtime 的 flushMinuteSnapshot（把内存累加器按当前整分钟 upsert 进
 * cdc_metric_minute，幂等可重入）。fail-open：realtime 不可达本轮跳过，下轮调度再来。
 */
@Component
public class CdcMetricFlushHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(CdcMetricFlushHandler.class);

    private final CdcOpsApi cdcOpsApi;

    public CdcMetricFlushHandler(CdcOpsApi cdcOpsApi) {
        this.cdcOpsApi = cdcOpsApi;
    }

    @Override
    public String getName() {
        return "cdcMetricFlushHandler";
    }

    @Override
    public void execute(String param) {
        Boolean ok = RemoteCalls.execute("realtime.cdc-metrics.flush-minute", () -> {
            Result<Void> result = cdcOpsApi.flushMinuteMetrics();
            return result == null ? null : true;
        }, null);
        if (ok == null) {
            logger.warn("CDC 分钟指标落库触发失败: realtime 不可达，本轮跳过");
        }
    }
}
