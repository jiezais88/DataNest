package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.QualityCheckStatsDTO;
import com.datanest.governance.service.QualityCheckQueryService;
import com.datanest.task.core.dto.QualityCheckBatchDTO;
import com.datanest.task.core.dto.QualityCheckQueryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 质量检查执行结果 Controller（Sprint 8 执行层）。
 * <p>
 * 提供批次分页查询与批次详情（含规则明细）。查看权限与质量任务一致（治理员/超管/工程师/分析师）。
 * 微服务化 4.2：查询走 governance 本地 QualityCheckQueryService（task-core 的 QualityCheckService
 * 只保留 worker 执行路径）。
 */
@Tag(name = "质量检查结果", description = "质量检查批次分页查询与批次详情（含规则明细）")
@RestController
@RequestMapping("/quality/checks")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityCheckController {

    private final QualityCheckQueryService checkQueryService;

    public QualityCheckController(QualityCheckQueryService checkQueryService) {
        this.checkQueryService = checkQueryService;
    }

    @Operation(summary = "批次分页列表", description = "按 job / trigger_type / status 过滤")
    @PostMapping("/page")
    public Result<PageResult<QualityCheckBatchDTO>> page(@RequestBody QualityCheckQueryRequest request) {
        return Result.ok(checkQueryService.listBatches(request));
    }

    @Operation(summary = "批次状态统计（顶部统计卡，按时间范围聚合）")
    @GetMapping("/stats")
    public Result<QualityCheckStatsDTO> stats(@Parameter(description = "时间下界（ISO 8601）") @RequestParam(required = false) String startTimeFrom,
                                              @Parameter(description = "时间上界（ISO 8601）") @RequestParam(required = false) String startTimeTo) {
        return Result.ok(checkQueryService.listStats(startTimeFrom, startTimeTo));
    }

    @Operation(summary = "批次详情（含规则明细）")
    @GetMapping("/{id}")
    public Result<QualityCheckBatchDTO> getById(@Parameter(description = "批次 ID") @PathVariable Long id) {
        return Result.ok(checkQueryService.getBatchDetail(id));
    }
}
