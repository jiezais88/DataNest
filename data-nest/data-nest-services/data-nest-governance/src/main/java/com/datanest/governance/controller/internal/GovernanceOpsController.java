package com.datanest.governance.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.AutoTriggerBindingRequest;
import com.datanest.governance.api.dto.AutoTriggeredBatchQueryRequest;
import com.datanest.governance.api.dto.CleanupRequest;
import com.datanest.governance.api.dto.QualityJobBindingDTO;
import com.datanest.governance.service.internal.ComplianceScanService;
import com.datanest.governance.service.internal.GovernanceOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * 治理域低频运维内部接口（实现 governance-api 的 GovernanceOpsApi 契约）。
 * <p>
 * 仅供服务间内部调用（data-nest-job 的定时清理/合规扫描/质量自动触发对账），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal")
public class GovernanceOpsController {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceOpsController.class);

    private final GovernanceOpsService governanceOpsService;
    private final ComplianceScanService complianceScanService;

    public GovernanceOpsController(GovernanceOpsService governanceOpsService,
                                   ComplianceScanService complianceScanService) {
        this.governanceOpsService = governanceOpsService;
        this.complianceScanService = complianceScanService;
    }

    /**
     * 清理超过保留天数的采集历史（级联变更明细 + 执行日志），返回删除总条数。
     */
    @PostMapping("/collect/cleanup")
    public Result<Integer> cleanupCollectHistory(@RequestBody CleanupRequest request) {
        return Result.ok(governanceOpsService.cleanupCollectHistory(request.getRetainDays()));
    }

    /**
     * 清理超过保留天数的质量检查历史（分批 500，级联明细），返回删除总条数。
     */
    @PostMapping("/quality/cleanup")
    public Result<Integer> cleanupQualityCheckHistory(@RequestBody CleanupRequest request) {
        return Result.ok(governanceOpsService.cleanupQualityCheckHistory(request.getRetainDays()));
    }

    /**
     * 清理超过保留天数的血缘记录，返回删除条数。
     */
    @PostMapping("/lineage/cleanup")
    public Result<Integer> cleanupLineageRecord(@RequestBody CleanupRequest request) {
        return Result.ok(governanceOpsService.cleanupLineageRecord(request.getRetainDays()));
    }

    /**
     * 标准合规全量扫描（全部在线数据源，命名 + 字段类型），返回新增不合规结果数。
     */
    @PostMapping("/compliance/run-checks")
    public Result<Integer> runComplianceChecks() {
        int count = complianceScanService.runFullScan();
        logger.info("标准合规全量扫描完成: 不合规项={}", count);
        return Result.ok(count);
    }

    /**
     * 查询 DAG 节点上绑定的启用质量任务（质量自动触发对账用）。
     */
    @PostMapping("/quality/auto-trigger-bindings")
    public Result<List<QualityJobBindingDTO>> autoTriggerBindings(@RequestBody AutoTriggerBindingRequest request) {
        return Result.ok(governanceOpsService.autoTriggerBindings(request.getDagNodeIds()));
    }

    /**
     * 查询指定质量任务自某时间点起已有的 AUTO_TRIGGER 批次（返回去重 jobId 列表）。
     */
    @PostMapping("/quality/batches/auto-triggered-since")
    public Result<List<Long>> autoTriggeredJobIdsSince(@RequestBody AutoTriggeredBatchQueryRequest request) {
        return Result.ok(governanceOpsService.autoTriggeredJobIdsSince(request.getJobIds(), request.getSince()));
    }
}
