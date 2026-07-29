package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.engineering.dto.*;
import com.datanest.engineering.service.SyncJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sync-jobs")
public class SyncJobController {

    private final SyncJobService syncJobService;

    public SyncJobController(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<SyncJobDTO> create(@Valid @RequestBody SyncJobCreateRequest request) {
        return Result.ok(syncJobService.create(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<SyncJobDTO> update(@PathVariable Long id, @Valid @RequestBody SyncJobUpdateRequest request) {
        return Result.ok(syncJobService.update(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        syncJobService.delete(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<SyncJobDTO> getById(@PathVariable Long id) {
        return Result.ok(syncJobService.getById(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<SyncJobDTO>> list(@RequestBody SyncJobQueryRequest request) {
        return Result.ok(syncJobService.list(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/execute")
    public Result<Void> execute(@PathVariable Long id) {
        syncJobService.execute(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/start")
    public Result<Void> startSchedule(@PathVariable Long id) {
        syncJobService.startSchedule(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/schedule/stop")
    public Result<Void> stopSchedule(@PathVariable Long id) {
        syncJobService.stopSchedule(id);
        return Result.ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping("/{id}/history/page")
    public Result<PageResult<SyncJobHistoryDTO>> historyPage(@PathVariable Long id,
                                                             @Valid @RequestBody SyncJobHistoryQueryRequest request) {
        return Result.ok(syncJobService.historyPage(id, request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @GetMapping("/{id}/history/{historyId}/logs")
    public Result<List<SyncJobLogDTO>> logs(@PathVariable Long id, @PathVariable Long historyId) {
        return Result.ok(syncJobService.getLogs(historyId));
    }
}
