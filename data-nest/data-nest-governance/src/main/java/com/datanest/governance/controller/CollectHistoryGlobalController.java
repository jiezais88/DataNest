package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.CollectHistoryDTO;
import com.datanest.governance.dto.CollectHistoryQueryRequest;
import com.datanest.governance.service.CollectHistoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/collect-tasks/global-history")
public class CollectHistoryGlobalController {

    private final CollectHistoryService collectHistoryService;

    public CollectHistoryGlobalController(CollectHistoryService collectHistoryService) {
        this.collectHistoryService = collectHistoryService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
    @PostMapping("/page")
    public Result<PageResult<CollectHistoryDTO>> list(@RequestBody @Valid CollectHistoryQueryRequest request) {
        return Result.ok(collectHistoryService.list(request));
    }
}
