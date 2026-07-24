package com.datanest.engineering.job;

import com.datanest.engineering.service.DataSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataSourceStatusRefreshJob {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceStatusRefreshJob.class);

    private final DataSourceService dataSourceService;

    public DataSourceStatusRefreshJob(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 每 5 分钟刷新一次数据源连接状态。
     * 生产环境可通过 datanest.datasource.refresh-cron 覆盖。
     */
    @Scheduled(cron = "${datanest.datasource.refresh-cron:0 0/5 * * * ?}")
    public void refresh() {
        logger.info("Starting scheduled data source status refresh");
        dataSourceService.refreshAllStatuses();
        logger.info("Scheduled data source status refresh completed");
    }
}
