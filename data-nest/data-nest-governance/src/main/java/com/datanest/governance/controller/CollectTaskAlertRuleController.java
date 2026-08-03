package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.dto.AlertRuleDTO;
import com.datanest.task.core.service.AlertRuleService;
import org.springframework.web.bind.annotation.*;

/**
 * 采集任务告警配置快捷入口（Sprint 5）。
 * 与全局告警中心操作同一数据源（alert_rule），任何入口修改实时同步。
 * 权限（PRD §8）：查看 = 超管/工程师/治理员；编辑 = 超管/工程师。
 */
@RestController
@RequestMapping("/collect-tasks")
public class CollectTaskAlertRuleController {

    private final AlertRuleService alertRuleService;

    public CollectTaskAlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/alert-rule")
    public Result<AlertRuleDTO> getAlertRule(@PathVariable Long id) {
        return Result.ok(alertRuleService.getRuleByObject(AlertConstants.OBJECT_TYPE_COLLECT_TASK, id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}/alert-rule")
    public Result<AlertRuleDTO> updateAlertRule(@PathVariable Long id, @RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.upsertRuleByObject(AlertConstants.OBJECT_TYPE_COLLECT_TASK, id, dto));
    }
}
