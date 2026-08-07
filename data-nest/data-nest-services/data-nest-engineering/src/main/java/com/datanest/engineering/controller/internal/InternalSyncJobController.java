package com.datanest.engineering.controller.internal;

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
import com.datanest.engineering.service.SyncJobTriggerService;
import com.datanest.engineering.service.internal.InternalSyncJobService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务域内部接口（实现 engineering-api 的 EngineeringSyncJobApi 契约）。
 * <p>
 * Controller 只做参数校验与转发，状态翻转/日志续号/收割/清理逻辑在
 * {@link InternalSyncJobService}（@Transactional 保持原子性）。
 */
@RestController
@RequestMapping("/internal/sync-jobs")
public class InternalSyncJobController {

    private final InternalSyncJobService syncJobService;
    private final SyncJobTriggerService syncJobTriggerService;

    public InternalSyncJobController(InternalSyncJobService syncJobService,
                                     SyncJobTriggerService syncJobTriggerService) {
        this.syncJobService = syncJobService;
        this.syncJobTriggerService = syncJobTriggerService;
    }

    // ==================== 任务定义与状态 ====================

    @GetMapping("/{id}")
    public Result<SyncJobInfo> getById(@PathVariable Long id) {
        return Result.ok(syncJobService.getJobById(id));
    }

    @GetMapping("/by-datasource/{datasourceId}")
    public Result<List<SyncJobInfo>> listByDatasource(@PathVariable Long datasourceId) {
        return Result.ok(syncJobService.listByDatasource(datasourceId));
    }

    @PostMapping("/{id}/mark-running")
    public Result<Boolean> markRunning(@PathVariable Long id) {
        return Result.ok(syncJobService.markRunning(id));
    }

    @PostMapping("/{id}/mark-status")
    public Result<Boolean> markStatus(@PathVariable Long id, @RequestBody SyncStatusMarkRequest request) {
        return Result.ok(syncJobService.markStatus(id, request));
    }

    @PostMapping("/{id}/finish-execution")
    public Result<Void> finishExecution(@PathVariable Long id, @RequestBody FinishExecutionRequest request) {
        syncJobService.finishExecution(id, request);
        return Result.ok(null);
    }

    @PutMapping("/{id}/scheduler-job-id")
    public Result<Void> updateSchedulerJobId(@PathVariable Long id, @RequestBody SchedulerJobIdUpdateRequest request) {
        syncJobService.updateSchedulerJobId(id, request.getSchedulerJobId());
        return Result.ok(null);
    }

    /** 触发同步任务执行（按需注册 PowerJob + mark-running + 建 RUNNING 历史 + 投递 PowerJob），返回 history id */
    @PostMapping("/{id}/trigger")
    public Result<Long> trigger(@PathVariable Long id, @RequestBody SyncJobTriggerRequest request) {
        return Result.ok(syncJobTriggerService.triggerSyncJob(id, request.getTriggerType(), request.getDagExecutionId()));
    }

    // ==================== 执行历史与日志 ====================

    @PostMapping("/histories")
    public Result<Long> createHistory(@RequestBody SyncHistoryCreateRequest request) {
        return Result.ok(syncJobService.createHistory(request));
    }

    @GetMapping("/histories/{id}")
    public Result<SyncHistoryInfo> getHistory(@PathVariable Long id) {
        return Result.ok(syncJobService.getHistoryLight(id));
    }

    @PostMapping("/histories/{id}/finish")
    public Result<Void> finishHistory(@PathVariable Long id, @RequestBody SyncHistoryFinishRequest request) {
        syncJobService.finishHistory(id, request);
        return Result.ok(null);
    }

    @GetMapping("/histories/latest")
    public Result<SyncHistoryInfo> latestHistory(
            @RequestParam Long syncJobId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime notBefore) {
        return Result.ok(syncJobService.latestHistory(syncJobId, notBefore));
    }

    @GetMapping("/histories/success-count")
    public Result<Long> successCount(@RequestParam Long syncJobId) {
        return Result.ok(syncJobService.successCount(syncJobId));
    }

    @PostMapping("/histories/{id}/logs:append")
    public Result<Void> appendLogs(@PathVariable Long id, @RequestBody SyncLogAppendRequest request) {
        syncJobService.appendLogs(id, request);
        return Result.ok(null);
    }

    // ==================== 重试 ====================

    @GetMapping("/histories/due-retries")
    public Result<List<SyncHistoryInfo>> dueRetries(@RequestParam(defaultValue = "50") Integer limit) {
        return Result.ok(syncJobService.dueRetries(limit));
    }

    @PostMapping("/histories/{id}/register-retry")
    public Result<Void> registerRetry(@PathVariable Long id, @RequestBody RegisterRetryRequest request) {
        syncJobService.registerRetry(id, request.getNextRetryAt());
        return Result.ok(null);
    }

    @PostMapping("/histories/{id}/claim-retry")
    public Result<Boolean> claimRetry(@PathVariable Long id) {
        return Result.ok(syncJobService.claimRetry(id));
    }

    @PostMapping("/histories/{id}/mark-failed")
    public Result<Void> markHistoryFailed(@PathVariable Long id, @RequestBody HistoryMarkFailedRequest request) {
        syncJobService.markHistoryFailed(id, request.getErrorMessage());
        return Result.ok(null);
    }

    // ==================== 批量操作 ====================

    @PostMapping("/reap-stuck")
    public Result<Integer> reapStuck(@RequestBody(required = false) ReapStuckRequest request) {
        return Result.ok(syncJobService.reapStuckSync(request == null ? null : request.getStuckBeforeMinutes()));
    }

    @PostMapping("/histories/cleanup")
    public Result<Integer> cleanupHistories(@RequestBody(required = false) CleanupRequest request) {
        return Result.ok(syncJobService.cleanupHistories(request == null ? null : request.getRetainDays()));
    }
}
