package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.*;
import com.datanest.engineering.service.SyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "同步任务", description = "批量数据同步任务 CRUD / 执行 / 调度启停 / 历史 / 日志")
@RestController
@RequestMapping("/sync-jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @Operation(summary = "创建同步任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<SyncJobDTO> create(@Valid @RequestBody SyncJobCreateRequest request) {
        return Result.ok(syncJobService.create(request));
    }

    @Operation(summary = "修改同步任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<SyncJobDTO> update(@Parameter(description = "任务 ID") @PathVariable Long id, @Valid @RequestBody SyncJobUpdateRequest request) {
        return Result.ok(syncJobService.update(id, request));
    }

    @Operation(summary = "删除同步任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "任务 ID") @PathVariable Long id) {
        syncJobService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "同步任务详情")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<SyncJobDTO> getById(@Parameter(description = "任务 ID") @PathVariable Long id) {
        return Result.ok(syncJobService.getById(id));
    }

    @Operation(summary = "同步任务分页查询")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<SyncJobDTO>> list(@RequestBody SyncJobQueryRequest request) {
        return Result.ok(syncJobService.list(request));
    }

    @Operation(summary = "同步任务状态统计（顶部统计卡）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/stats")
    public Result<SyncJobStatsDTO> stats() {
        return Result.ok(syncJobService.listStats());
    }

    @Operation(summary = "手动执行同步任务")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/execute")
    public Result<Void> execute(@Parameter(description = "任务 ID") @PathVariable Long id) {
        syncJobService.execute(id);
        return Result.ok(null);
    }

    @Operation(summary = "开启同步任务定时调度")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@Parameter(description = "任务 ID") @PathVariable Long id) {
        syncJobService.startSchedule(id);
        return Result.ok(null);
    }

    @Operation(summary = "停止同步任务定时调度")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@Parameter(description = "任务 ID") @PathVariable Long id) {
        syncJobService.stopSchedule(id);
        return Result.ok(null);
    }

    @Operation(summary = "单任务执行历史分页")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/history/page")
    public Result<PageResult<SyncJobHistoryDTO>> historyPage(@Parameter(description = "任务 ID") @PathVariable Long id,
                                                             @Valid @RequestBody SyncJobHistoryQueryRequest request) {
        return Result.ok(syncJobService.historyPage(id, request));
    }

    @Operation(summary = "停止执行中的历史记录")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/history/{historyId}/stop")
    public Result<Void> stopHistory(@Parameter(description = "历史记录 ID") @PathVariable Long historyId) {
        syncJobService.stopHistory(historyId);
        return Result.ok(null);
    }

    @Operation(summary = "全局执行历史分页")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/history/page")
    public Result<PageResult<SyncJobHistoryDTO>> allHistoryPage(@Valid @RequestBody SyncJobHistoryQueryRequest request) {
        // 全局历史接口也支持按 syncJobId 精确过滤（从任务列表「历史」跳入时 URL 带 ?syncJobId=xxx）
        return Result.ok(syncJobService.historyPage(request.getSyncJobId(), request));
    }

    @Operation(summary = "执行历史状态统计（顶部统计卡，按时间范围聚合）")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/history/stats")
    public Result<SyncJobHistoryStatsDTO> historyStats(@Parameter(description = "执行时间下界（ISO 8601）") @RequestParam(required = false) String startTimeFrom,
                                                       @Parameter(description = "执行时间上界（ISO 8601）") @RequestParam(required = false) String startTimeTo) {
        return Result.ok(syncJobService.listHistoryStats(startTimeFrom, startTimeTo));
    }

    @Operation(summary = "执行历史日志分页")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/{id}/history/{historyId}/logs")
    public Result<PageResult<SyncJobLogDTO>> logs(@Parameter(description = "任务 ID") @PathVariable Long id,
                                                  @Parameter(description = "历史记录 ID") @PathVariable Long historyId,
                                                  @Parameter(description = "日志范围（all=全部，否则按表过滤）") @RequestParam(defaultValue = "all") String scope,
                                                  @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                  @Parameter(description = "每页条数") @RequestParam(defaultValue = "200") int pageSize) {
        return Result.ok(syncJobService.getLogs(historyId, scope, page, pageSize));
    }
}
