package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.CollectExecutionLogDTO;
import com.datanest.governance.dto.CollectHistoryDTO;
import com.datanest.governance.dto.CollectHistoryQueryRequest;
import com.datanest.governance.service.CollectHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "采集历史", description = "单任务采集历史分页与执行日志查询")
@RestController
@RequestMapping("/collect-tasks/{taskId}/history")
public class CollectHistoryController {

    private final CollectHistoryService collectHistoryService;

    public CollectHistoryController(CollectHistoryService collectHistoryService) {
        this.collectHistoryService = collectHistoryService;
    }

    @Operation(summary = "采集历史分页列表")
    @SaCheckPermission(PermissionCode.COLLECT_HISTORY)
    @PostMapping("/page")
    public Result<PageResult<CollectHistoryDTO>> list(@Parameter(description = "采集任务 ID") @PathVariable Long taskId,
                                                      @RequestBody @Valid CollectHistoryQueryRequest request) {
        request.setTaskId(taskId);
        return Result.ok(collectHistoryService.list(request));
    }

    @Operation(summary = "采集历史详情")
    @SaCheckPermission(PermissionCode.COLLECT_HISTORY)
    @GetMapping("/{historyId}")
    public Result<CollectHistoryDTO> getById(@Parameter(description = "采集任务 ID") @PathVariable Long taskId,
                                             @Parameter(description = "历史 ID") @PathVariable Long historyId) {
        return Result.ok(collectHistoryService.getById(historyId));
    }

    @Operation(summary = "采集执行日志")
    @SaCheckPermission(PermissionCode.COLLECT_HISTORY)
    @GetMapping("/{historyId}/logs")
    public Result<List<CollectExecutionLogDTO>> getLogs(@Parameter(description = "采集任务 ID") @PathVariable Long taskId,
                                                        @Parameter(description = "历史 ID") @PathVariable Long historyId) {
        return Result.ok(collectHistoryService.getLogs(historyId));
    }
}
