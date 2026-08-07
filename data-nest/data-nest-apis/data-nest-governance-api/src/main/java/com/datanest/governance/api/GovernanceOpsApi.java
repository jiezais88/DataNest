package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.AutoTriggerBindingRequest;
import com.datanest.governance.api.dto.AutoTriggeredBatchQueryRequest;
import com.datanest.governance.api.dto.CleanupRequest;
import com.datanest.governance.api.dto.QualityJobBindingDTO;
import com.datanest.governance.api.fallback.GovernanceOpsApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 治理域低频运维内部 Feign 契约。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-governance 的 /governance/internal/** 端点。
 * 微服务化 4.1：采集历史/质量历史/血缘清理、标准合规全量扫描、质量自动触发对账查询
 * 从 data-nest-job 下沉至治理服务。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "governanceOpsApi",
        fallbackFactory = GovernanceOpsApiFallbackFactory.class)
public interface GovernanceOpsApi {

    /** 清理超过保留天数的采集历史（级联变更明细 + 执行日志），返回删除总条数 */
    @PostMapping("/collect/cleanup")
    Result<Integer> cleanupCollectHistory(@RequestBody CleanupRequest request);

    /** 清理超过保留天数的质量检查历史（分批 500，级联明细），返回删除总条数 */
    @PostMapping("/quality/cleanup")
    Result<Integer> cleanupQualityCheckHistory(@RequestBody CleanupRequest request);

    /** 清理超过保留天数的血缘记录，返回删除条数 */
    @PostMapping("/lineage/cleanup")
    Result<Integer> cleanupLineageRecord(@RequestBody CleanupRequest request);

    /** 标准合规全量扫描（全部在线数据源，命名 + 字段类型），返回新增结果数 */
    @PostMapping("/compliance/run-checks")
    Result<Integer> runComplianceChecks();

    /** 查询 DAG 节点上绑定的启用质量任务（enabled + auto_trigger_enabled + DAG_NODE） */
    @PostMapping("/quality/auto-trigger-bindings")
    Result<List<QualityJobBindingDTO>> autoTriggerBindings(@RequestBody AutoTriggerBindingRequest request);

    /** 查询指定质量任务自某时间点起已有的 AUTO_TRIGGER 批次（返回去重 jobId 列表） */
    @PostMapping("/quality/batches/auto-triggered-since")
    Result<List<Long>> autoTriggeredJobIdsSince(@RequestBody AutoTriggeredBatchQueryRequest request);
}
