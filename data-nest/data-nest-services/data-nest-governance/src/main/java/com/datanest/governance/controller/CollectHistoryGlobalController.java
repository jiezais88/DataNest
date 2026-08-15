package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.CollectHistoryDTO;
import com.datanest.governance.dto.CollectHistoryQueryRequest;
import com.datanest.governance.dto.CollectHistoryStatsDTO;
import com.datanest.governance.service.CollectHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// 类级路径上移到 /collect-tasks，以便同时承载全局历史查询与按历史 ID 停止两个入口
@Tag(name = "采集历史（全局）", description = "全局采集历史查询与按历史 ID 停止")
@RestController
@RequestMapping("/collect-tasks")
public class CollectHistoryGlobalController {

    private final CollectHistoryService collectHistoryService;

    public CollectHistoryGlobalController(CollectHistoryService collectHistoryService) {
        this.collectHistoryService = collectHistoryService;
    }

    @Operation(summary = "全局采集历史分页列表")
    @SaCheckPermission(PermissionCode.COLLECT_HISTORY)
    @PostMapping("/global-history/page")
    public Result<PageResult<CollectHistoryDTO>> list(@RequestBody @Valid CollectHistoryQueryRequest request) {
        return Result.ok(collectHistoryService.list(request));
    }

    @Operation(summary = "全局采集历史状态统计（顶部统计卡，按时间范围聚合）")
    @SaCheckPermission(PermissionCode.COLLECT_HISTORY)
    @GetMapping("/global-history/stats")
    public Result<CollectHistoryStatsDTO> stats(@Parameter(description = "时间下界（ISO 8601）") @RequestParam(required = false) String startTimeFrom,
                                                @Parameter(description = "时间上界（ISO 8601）") @RequestParam(required = false) String startTimeTo) {
        return Result.ok(collectHistoryService.listStats(startTimeFrom, startTimeTo));
    }

    // 停止为写操作，权限对齐采集任务的 execute/schedule，仅治理写角色可用
    @Operation(summary = "停止采集执行")
    @SaCheckPermission(PermissionCode.COLLECT_EXECUTE)
    @PostMapping("/history/{historyId}/stop")
    public Result<Void> stop(@Parameter(description = "历史 ID") @PathVariable Long historyId) {
        collectHistoryService.stop(historyId);
        return Result.ok(null);
    }
}
