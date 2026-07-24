package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.CollectExecutionLogDTO;
import com.datanest.governance.dto.CollectHistoryDTO;
import com.datanest.governance.dto.CollectHistoryQueryRequest;
import com.datanest.governance.service.CollectHistoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/collect-tasks/{taskId}/history")
public class CollectHistoryController {

    private final CollectHistoryService collectHistoryService;

    public CollectHistoryController(CollectHistoryService collectHistoryService) {
        this.collectHistoryService = collectHistoryService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<CollectHistoryDTO>> list(@PathVariable Long taskId,
                                                      @RequestBody @Valid CollectHistoryQueryRequest request) {
        request.setTaskId(taskId);
        return Result.ok(collectHistoryService.list(request));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{historyId}")
    public Result<CollectHistoryDTO> getById(@PathVariable Long taskId, @PathVariable Long historyId) {
        return Result.ok(collectHistoryService.getById(historyId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
    @GetMapping("/{historyId}/logs")
    public Result<List<CollectExecutionLogDTO>> getLogs(@PathVariable Long taskId, @PathVariable Long historyId) {
        return Result.ok(collectHistoryService.getLogs(historyId));
    }
}
