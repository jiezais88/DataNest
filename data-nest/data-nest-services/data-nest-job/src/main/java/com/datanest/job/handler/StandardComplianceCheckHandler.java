package com.datanest.job.handler;

import com.datanest.task.core.dto.ComplianceCheckRequest;
import com.datanest.task.core.service.ComplianceCheckService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标准合规定时扫描任务（全局一个 cron）。
 * 扫描全部在线数据源，按已启用的命名规范 + 字段类型标准比对，生成/更新合规检查结果。
 * 调度由 JobRegistrar.platformJobs 注册固定 cron。
 */
@Component
public class StandardComplianceCheckHandler {

    private static final Logger logger = LoggerFactory.getLogger(StandardComplianceCheckHandler.class);

    private final ComplianceCheckService complianceCheckService;

    public StandardComplianceCheckHandler(ComplianceCheckService complianceCheckService) {
        this.complianceCheckService = complianceCheckService;
    }

    @Transactional
    @XxlJob("standardComplianceCheckHandler")
    public void run() {
        logger.info("Starting standard compliance check (all online datasources)");
        try {
            ComplianceCheckRequest request = new ComplianceCheckRequest();
            // 不指定范围：resolveDatasourceIds 会取全部在线数据源，命名+字段类型均检查
            int count = complianceCheckService.check(request).size();
            logger.info("Standard compliance check completed: violations={}", count);
            XxlJobHelper.handleSuccess("标准合规扫描完成: 不合规项=" + count);
        } catch (Exception e) {
            logger.error("Standard compliance check failed", e);
            XxlJobHelper.handleFail("标准合规扫描失败: " + e.getMessage());
        }
    }
}
