package com.datanest.job.handler;

import com.datanest.task.core.service.DataSourceRefreshService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 定时刷新数据源连接状态。
 */
@Component
public class DataSourceStatusRefreshHandler {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceStatusRefreshHandler.class);

    private final DataSourceRefreshService dataSourceRefreshService;

    public DataSourceStatusRefreshHandler(DataSourceRefreshService dataSourceRefreshService) {
        this.dataSourceRefreshService = dataSourceRefreshService;
    }

    @XxlJob("dataSourceStatusRefreshHandler")
    public void refresh() {
        logger.info("Starting scheduled data source status refresh");
        try {
            dataSourceRefreshService.refreshAllStatuses();
            XxlJobHelper.handleSuccess();
            logger.info("Scheduled data source status refresh completed");
        } catch (Exception e) {
            logger.error("Scheduled data source status refresh failed", e);
            XxlJobHelper.handleFail("数据源状态刷新失败: " + e.getMessage());
        }
    }
}
