package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityJobCreateRequest;
import com.datanest.task.core.dto.QualityJobDTO;
import com.datanest.task.core.dto.QualityJobQueryRequest;
import com.datanest.task.core.dto.QualityJobUpdateRequest;
import com.datanest.governance.dto.QualityJobStatsDTO;
import com.datanest.governance.service.QualityJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 质量任务 Controller（Sprint 6 配置层）。
 * <p>
 * 权限（对照 PRD §8 / 技术文档 §7）：查看为治理员/超管/工程师/分析师；新增/编辑/删除/启停/执行为治理员/超管。
 */
@Tag(name = "质量任务", description = "质量任务 CRUD / 启停 / 执行 / 调度")
@RestController
@RequestMapping("/quality/jobs")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityJobController {

    private final QualityJobService jobService;

    public QualityJobController(QualityJobService jobService) {
        this.jobService = jobService;
    }

    @Operation(summary = "质量任务分页列表", description = "含规则数、调度状态徽章")
    @PostMapping("/page")
    public Result<PageResult<QualityJobDTO>> page(@RequestBody QualityJobQueryRequest request) {
        return Result.ok(jobService.list(request));
    }

    @Operation(summary = "质量任务配置统计（顶部统计卡）")
    @GetMapping("/stats")
    public Result<QualityJobStatsDTO> stats() {
        return Result.ok(jobService.listStats());
    }

    @Operation(summary = "质量任务详情（含规则列表）")
    @GetMapping("/{id}")
    public Result<QualityJobDTO> getById(@Parameter(description = "质量任务 ID") @PathVariable Long id) {
        return Result.ok(jobService.getById(id));
    }

    @Operation(summary = "创建质量任务")
    @PostMapping
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityJobDTO> create(@Valid @RequestBody QualityJobCreateRequest request) {
        return Result.ok(jobService.create(request));
    }

    @Operation(summary = "编辑质量任务")
    @PutMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityJobDTO> update(@Parameter(description = "质量任务 ID") @PathVariable Long id,
                                        @Valid @RequestBody QualityJobUpdateRequest request) {
        return Result.ok(jobService.update(id, request));
    }

    @Operation(summary = "删除质量任务", description = "级联删除其下规则")
    @DeleteMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> delete(@Parameter(description = "质量任务 ID") @PathVariable Long id) {
        jobService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "启停质量任务")
    @PostMapping("/{id}/toggle")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityJobDTO> toggle(@Parameter(description = "质量任务 ID") @PathVariable Long id,
                                        @Parameter(description = "目标启用状态（不传则取反）") @RequestParam(required = false) Boolean enabled) {
        return Result.ok(jobService.toggle(id, enabled));
    }

    @Operation(summary = "手动执行质量任务")
    @PostMapping("/{id}/execute")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> execute(@Parameter(description = "质量任务 ID") @PathVariable Long id) {
        jobService.executeJob(id);
        return Result.ok(null);
    }

    @Operation(summary = "开启调度", description = "scheduled_enabled=1；仅切调度开关，cron 为空时抛错")
    @PostMapping("/{id}/schedule/start")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> startSchedule(@Parameter(description = "质量任务 ID") @PathVariable Long id) {
        jobService.startSchedule(id);
        return Result.ok(null);
    }

    @Operation(summary = "关闭调度", description = "scheduled_enabled=0；仅切调度开关")
    @PostMapping("/{id}/schedule/stop")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> stopSchedule(@Parameter(description = "质量任务 ID") @PathVariable Long id) {
        jobService.stopSchedule(id);
        return Result.ok(null);
    }
}
