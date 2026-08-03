package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.entity.AlertHistory;
import com.datanest.task.core.service.AlertRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局告警中心 - 告警历史（Sprint 5）。
 * 权限（PRD §8）：查看告警历史 = 超管/工程师/治理员。
 */
@RestController
@RequestMapping("/alert-history")
public class AlertHistoryController {

    private final AlertRuleService alertRuleService;

    public AlertHistoryController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<AlertHistory>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int pageSize,
                                                 @RequestParam(required = false) String objectType,
                                                 @RequestParam(required = false) Long objectId,
                                                 @RequestParam(required = false) String alertType) {
        return Result.ok(alertRuleService.listHistory(page, pageSize, objectType, objectId, alertType));
    }
}
