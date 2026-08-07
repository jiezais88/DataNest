package com.datanest.job.handler;

import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceOpsApi;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 标准合规定时扫描任务（全局一个 cron）。
 * 扫描全部在线数据源，按已启用的命名规范 + 字段类型标准比对，生成/更新合规检查结果。
 * 调度由 JobRegistrar.platformJobs 注册固定 cron。
 * <p>
 * 微服务化 4.3：合规检查表归治理域，全量扫描下沉 governance
 * （{@code POST /governance/internal/compliance/run-checks}），本 handler 只负责调度触发。
 * RemoteCalls 容错：governance 不可用本轮跳过，下轮调度再来。
 */
@Component
public class StandardComplianceCheckHandler {

    private static final Logger logger = LoggerFactory.getLogger(StandardComplianceCheckHandler.class);

    private final GovernanceOpsApi governanceOpsApi;

    public StandardComplianceCheckHandler(GovernanceOpsApi governanceOpsApi) {
        this.governanceOpsApi = governanceOpsApi;
    }

    @XxlJob("standardComplianceCheckHandler")
    public void run() {
        logger.info("Starting standard compliance check (all online datasources)");
        Integer count = RemoteCalls.execute("governance.ops.compliance-run-checks", () -> {
            Result<Integer> result = governanceOpsApi.runComplianceChecks();
            return result == null ? null : result.data();
        }, null);
        if (count == null) {
            XxlJobHelper.handleFail("标准合规扫描失败: governance 服务不可用，本轮跳过");
            return;
        }
        logger.info("Standard compliance check completed: violations={}", count);
        XxlJobHelper.handleSuccess("标准合规扫描完成: 不合规项=" + count);
    }
}
