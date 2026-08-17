package com.datanest.realtime.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcOpsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * CdcOpsApi 熔断降级工厂（2026-08-17）。
 * <p>
 * 定时触发场景一律 fail-open：realtime 不可达本轮跳过，下轮调度再来（对齐
 * CdcPipelineApi.refreshCatalogIfRunning 的降级语义），返回 null 由 job handler 侧
 * RemoteCalls 判空容错，不阻断调度主流程。
 */
@Component
public class CdcOpsApiFallbackFactory implements FallbackFactory<CdcOpsApi> {

    private static final Logger logger = LoggerFactory.getLogger(CdcOpsApiFallbackFactory.class);

    @Override
    public CdcOpsApi create(Throwable cause) {
        logger.warn("CdcOpsApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new CdcOpsApi() {
            @Override
            public Result<Void> pollRunningPipelines() {
                return null;
            }

            @Override
            public Result<Void> pollEventJobs() {
                return null;
            }

            @Override
            public Result<Void> flushMinuteMetrics() {
                return null;
            }

            @Override
            public Result<Void> cleanupMetrics() {
                return null;
            }
        };
    }
}
