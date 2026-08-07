package com.datanest.engineering.api;

import com.datanest.common.model.Result;
import com.datanest.engineering.api.dto.CleanupRequest;
import com.datanest.engineering.api.dto.FinishExecutionRequest;
import com.datanest.engineering.api.dto.HistoryMarkFailedRequest;
import com.datanest.engineering.api.dto.ReapStuckRequest;
import com.datanest.engineering.api.dto.RegisterRetryRequest;
import com.datanest.engineering.api.dto.SyncHistoryCreateRequest;
import com.datanest.engineering.api.dto.SyncHistoryFinishRequest;
import com.datanest.engineering.api.dto.SyncHistoryInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import com.datanest.engineering.api.dto.SyncJobTriggerRequest;
import com.datanest.engineering.api.dto.SyncLogAppendRequest;
import com.datanest.engineering.api.dto.SyncStatusMarkRequest;
import com.datanest.engineering.api.dto.SchedulerJobIdUpdateRequest;
import com.datanest.engineering.api.fallback.EngineeringSyncJobApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 同步任务域内部 Feign 契约（任务定义/状态翻转/执行历史与日志/重试/收割与清理）。
 */
@FeignClient(name = "data-nest-engineering", path = "/engineering/internal", contextId = "engineeringSyncJobApi",
        fallbackFactory = EngineeringSyncJobApiFallbackFactory.class)
public interface EngineeringSyncJobApi {

    // ==================== 任务定义与状态 ====================

    /** 按 id 查询同步任务（执行所需全部配置） */
    @GetMapping("/sync-jobs/{id}")
    Result<SyncJobInfo> getById(@PathVariable("id") Long id);

    /** 按源数据源 id 查询引用它的同步任务（删除数据源引用检查，只用 id/name） */
    @GetMapping("/sync-jobs/by-datasource/{datasourceId}")
    Result<List<SyncJobInfo>> listByDatasource(@PathVariable("datasourceId") Long datasourceId);

    /** execution_status → RUNNING */
    @PostMapping("/sync-jobs/{id}/mark-running")
    Result<Boolean> markRunning(@PathVariable("id") Long id);

    /**
     * execution_status 条件更新：expectedLastHistoryId 非空时保留
     * last_history_id 保护（仅当 last_history_id 为空或等于该值才翻转）。
     */
    @PostMapping("/sync-jobs/{id}/mark-status")
    Result<Boolean> markStatus(@PathVariable("id") Long id, @RequestBody SyncStatusMarkRequest request);

    /** 执行完成回写 last_execute_time + last_history_id */
    @PostMapping("/sync-jobs/{id}/finish-execution")
    Result<Void> finishExecution(@PathVariable("id") Long id, @RequestBody FinishExecutionRequest request);

    /** 回写 scheduler_job_id（PowerJob jobId） */
    @PutMapping("/sync-jobs/{id}/scheduler-job-id")
    Result<Void> updateSchedulerJobId(@PathVariable("id") Long id, @RequestBody SchedulerJobIdUpdateRequest request);

    /**
     * 触发同步任务执行（按需注册 PowerJob + mark-running + 新建 RUNNING 历史 + 投递 PowerJob），
     * 返回 sync_job_history.id。执行链路入口，远程失败按 fail-fast（data=null）处理。
     */
    @PostMapping("/sync-jobs/{id}/trigger")
    Result<Long> trigger(@PathVariable("id") Long id, @RequestBody SyncJobTriggerRequest request);

    // ==================== 执行历史与日志 ====================

    /** 新建 RUNNING 历史（覆盖 init 与 retry 插入），返回 history id */
    @PostMapping("/sync-jobs/histories")
    Result<Long> createHistory(@RequestBody SyncHistoryCreateRequest request);

    /** 单条历史轻量查询（停止 watcher 轮询用，服务端只查必要列） */
    @GetMapping("/sync-jobs/histories/{id}")
    Result<SyncHistoryInfo> getHistory(@PathVariable("id") Long id);

    /** 历史终态回写 */
    @PostMapping("/sync-jobs/histories/{id}/finish")
    Result<Void> finishHistory(@PathVariable("id") Long id, @RequestBody SyncHistoryFinishRequest request);

    /** 最新一条历史；notBefore 非空时只接受 end_time 不早于该值的记录（RUNNING 不收尾语义在调用方）。时间为 ISO 字符串（避免 Feign ConversionService locale 格式化问题） */
    @GetMapping("/sync-jobs/histories/latest")
    Result<SyncHistoryInfo> latestHistory(@RequestParam("syncJobId") Long syncJobId,
                                          @RequestParam(value = "notBefore", required = false) String notBefore);

    /** 成功历史条数（增量首跑判断） */
    @GetMapping("/sync-jobs/histories/success-count")
    Result<Long> successCount(@RequestParam("syncJobId") Long syncJobId);

    /** 追加同步日志（服务端续号 + 事务批量插入，500/批分片在服务端） */
    @PostMapping("/sync-jobs/histories/{id}/logs:append")
    Result<Void> appendLogs(@PathVariable("id") Long id, @RequestBody SyncLogAppendRequest request);

    // ==================== 重试 ====================

    /** 到期待重试的失败历史（next_retry_at <= now，按 next_retry_at 升序） */
    @GetMapping("/sync-jobs/histories/due-retries")
    Result<List<SyncHistoryInfo>> dueRetries(@RequestParam("limit") Integer limit);

    /** 登记下次重试时间 */
    @PostMapping("/sync-jobs/histories/{id}/register-retry")
    Result<Void> registerRetry(@PathVariable("id") Long id, @RequestBody RegisterRetryRequest request);

    /** 原子认领重试（清空 next_retry_at），返回是否认领成功 */
    @PostMapping("/sync-jobs/histories/{id}/claim-retry")
    Result<Boolean> claimRetry(@PathVariable("id") Long id);

    /** 重试历史收尾（标 FAILED，不清空 retry_count） */
    @PostMapping("/sync-jobs/histories/{id}/mark-failed")
    Result<Void> markHistoryFailed(@PathVariable("id") Long id, @RequestBody HistoryMarkFailedRequest request);

    // ==================== 批量操作（逻辑下沉 engineering，保持原子性） ====================

    /** 收割卡死 RUNNING 的 sync_job_history 并条件翻转 sync_job，返回处理数 */
    @PostMapping("/sync-jobs/reap-stuck")
    Result<Integer> reapStuck(@RequestBody ReapStuckRequest request);

    /** 清理 sync_job_history + sync_job_log，返回删除数（history + log 合计） */
    @PostMapping("/sync-jobs/histories/cleanup")
    Result<Integer> cleanupHistories(@RequestBody CleanupRequest request);
}
