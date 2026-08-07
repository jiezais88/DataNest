package com.datanest.engineering.api;

import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.CleanupRequest;
import com.datanest.engineering.api.dto.DagExecutionCreateRequest;
import com.datanest.engineering.api.dto.DagExecutionFinalizeRequest;
import com.datanest.engineering.api.dto.DagExecutionInfo;
import com.datanest.engineering.api.dto.EnsureDagExecutionRequest;
import com.datanest.engineering.api.dto.NodeExecutionBatchUpdateRequest;
import com.datanest.engineering.api.dto.NodeExecutionInfo;
import com.datanest.engineering.api.dto.NodeExecutionMarkRequest;
import com.datanest.engineering.api.dto.NodeLogAppendRequest;
import com.datanest.engineering.api.dto.ReapStuckRequest;
import com.datanest.engineering.api.fallback.EngineeringDagExecutionApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * DAG 执行记录域内部 Feign 契约（worker 回调 + job 同步器共用）。
 */
@FeignClient(name = "data-nest-engineering", path = "/engineering/internal", contextId = "engineeringDagExecutionApi",
        fallbackFactory = EngineeringDagExecutionApiFallbackFactory.class)
public interface EngineeringDagExecutionApi {

    // ==================== 执行实例 ====================

    /** RUNNING 执行分页（job 每 5s 扫） */
    @GetMapping("/dag-executions/running")
    Result<PageResult<DagExecutionInfo>> listRunning(@RequestParam("page") Integer page,
                                                     @RequestParam("pageSize") Integer pageSize);

    /** 按 id 查执行实例（含 resolved_params/edges_snapshot/ds_process_instance_id） */
    @GetMapping("/dag-executions/{id}")
    Result<DagExecutionInfo> getById(@PathVariable("id") Long id);

    /** 按 DS 流程实例 id 查执行实例（可空） */
    @GetMapping("/dag-executions/by-ds-instance/{dsProcessInstanceId}")
    Result<DagExecutionInfo> getByDsInstance(@PathVariable("dsProcessInstanceId") Long dsProcessInstanceId);

    /** ensureDagExecution：插执行 + 批量插节点，一个事务，返回 execution id */
    @PostMapping("/dag-executions")
    Result<Long> createExecution(@RequestBody DagExecutionCreateRequest request);

    /**
     * P3：按 PowerJob 工作流实例补齐执行记录（worker 处理 cron 触发实例时调用）。
     * 若该 wfInstanceId 已有 dag_execution 则直接返回其 id；否则创建（triggerType=SCHEDULED）
     * 并预创建全量 WAITING node_execution，返回 dagExecutionId。
     */
    @PostMapping("/dag/ensure-execution")
    Result<Long> ensureExecution(@RequestBody EnsureDagExecutionRequest request);

    /** 终态回写；服务端落库后触发 DAG 完成副作用（进程内 Feign 调 app-alert dagFinished） */
    @PostMapping("/dag-executions/{id}/finalize")
    Result<Void> finalizeExecution(@PathVariable("id") Long id, @RequestBody DagExecutionFinalizeRequest request);

    /** 时间段内 SUCCESS 的执行（质量对账扫描用），按 start_time 过滤、id 升序。时间参数用 ISO 字符串（LocalDateTime 经 Feign ConversionService 会被 locale 格式化，服务端解析失败） */
    @GetMapping("/dag-executions/succeeded-between")
    Result<List<DagExecutionInfo>> succeededBetween(@RequestParam("from") String from,
                                                    @RequestParam("to") String to,
                                                    @RequestParam("limit") Integer limit);

    /** 收割卡死 RUNNING 的 dag_execution + node_execution，返回处理数 */
    @PostMapping("/dag-executions/reap-stuck")
    Result<Integer> reapStuck(@RequestBody ReapStuckRequest request);

    /** 清理 N 天前终态执行及其 node_execution（500/批），返回删除的 dag_execution 数 */
    @PostMapping("/dag-executions/cleanup")
    Result<Integer> cleanup(@RequestBody CleanupRequest request);

    // ==================== 节点执行 ====================

    /** 执行实例下全部节点（全字段含 version/output_info/sync_job_id/sync_job_history_id） */
    @GetMapping("/dag-executions/{id}/nodes")
    Result<List<NodeExecutionInfo>> listNodes(@PathVariable("id") Long id);

    /** 批量乐观锁更新；version 不匹配的跳过，返回失败 id 列表 */
    @PostMapping("/node-executions/batch-update")
    Result<List<Long>> batchUpdateNodes(@RequestBody NodeExecutionBatchUpdateRequest request);

    /** 节点状态机单点更新；expectedStatus 非空时条件更新，返回是否成功 */
    @PostMapping("/node-executions/{id}/mark")
    Result<Boolean> markNode(@PathVariable("id") Long id, @RequestBody NodeExecutionMarkRequest request);

    /** 把执行实例下未结束节点（WAITING/RUNNING）标 SKIPPED，返回处理数 */
    @PostMapping("/dag-executions/{id}/nodes/mark-skipped")
    Result<Integer> markNodesSkipped(@PathVariable("id") Long id);

    /** RUNNING 节点（含 dagId，join 在服务端做，超时告警扫描用） */
    @GetMapping("/node-executions/running-with-dag")
    Result<List<NodeExecutionInfo>> runningWithDag(@RequestParam("limit") Integer limit);

    /** 按 syncJobId 查未结束（RUNNING/WAITING）节点执行 */
    @GetMapping("/node-executions/running-by-sync-job/{syncJobId}")
    Result<List<NodeExecutionInfo>> runningBySyncJob(@PathVariable("syncJobId") Long syncJobId);

    /** 追加节点日志（服务端按 executionId + nodeId 续号，同事务批量插入） */
    @PostMapping("/node-executions/{id}/logs:append")
    Result<Void> appendNodeLogs(@PathVariable("id") Long id, @RequestBody NodeLogAppendRequest request);
}
