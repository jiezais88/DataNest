package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcPipelineApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Doris 湖仓 catalog 定时自动刷新（2026-08-11，Sprint 8 人工验收反馈）。
 * <p>
 * 背景：CDC 数据落湖（Iceberg commit）是即时的，但 Doris 外部 catalog 缓存 snapshot 元数据，
 * 不刷新要等 FE 默认最短刷新时间（10 分钟级）才可见，用户以为"必须手动点刷新 Catalog"。
 * 本 handler 定时触发 realtime 的条件刷新端点（存在 RUNNING 管道才 REFRESH CATALOG，无运行管道不空转）。
 * 手动「刷新 Catalog」按钮保留（立等可取场景）。
 * RemoteCalls 容错：realtime 不可用本轮跳过，下轮调度再来。
 */
@Component
public class DorisCatalogAutoRefreshHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DorisCatalogAutoRefreshHandler.class);

    private final CdcPipelineApi cdcPipelineApi;

    public DorisCatalogAutoRefreshHandler(CdcPipelineApi cdcPipelineApi) {
        this.cdcPipelineApi = cdcPipelineApi;
    }

    @Override
    public String getName() {
        return "dorisCatalogAutoRefreshHandler";
    }

    @Override
    public void execute(String param) {
        Boolean refreshed = RemoteCalls.execute("realtime.cdc.refresh-catalog-if-running", () -> {
            Result<Boolean> result = cdcPipelineApi.refreshCatalogIfRunning();
            return result == null ? null : result.data();
        }, null);
        if (refreshed == null) {
            throw new IllegalStateException("湖仓 catalog 自动刷新失败: realtime 服务不可用，本轮跳过");
        }
        if (Boolean.TRUE.equals(refreshed)) {
            logger.info("Doris catalog auto-refresh done (running pipelines exist)");
        }
    }
}
