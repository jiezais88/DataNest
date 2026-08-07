package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 定时刷新数据源连接状态。
 * <p>
 * 微服务化 4.3：datasource_connection 归 engineering，刷新逻辑（逐个连接测试 + 状态回写）
 * 下沉 engineering 内部端点 {@code POST /engineering/internal/datasources/refresh-statuses}，
 * 本 handler 只负责调度触发。RemoteCalls 容错：engineering 不可用本轮跳过，下轮调度再来。
 */
@Component
public class DataSourceStatusRefreshHandler implements PlatformJobHandler {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceStatusRefreshHandler.class);

    private final EngineeringDatasourceApi datasourceApi;

    public DataSourceStatusRefreshHandler(EngineeringDatasourceApi datasourceApi) {
        this.datasourceApi = datasourceApi;
    }

    @Override
    public String getName() {
        return "dataSourceStatusRefreshHandler";
    }

    @Override
    public void execute(String param) {
        logger.info("Starting scheduled data source status refresh");
        RemoteCalls.execute("engineering.datasource.refresh-statuses", () -> datasourceApi.refreshStatuses());
        logger.info("Scheduled data source status refresh triggered");
    }
}
