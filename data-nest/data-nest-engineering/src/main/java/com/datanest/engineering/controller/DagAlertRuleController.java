package com.datanest.engineering.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.task.core.constant.AlertConstants;
import com.datanest.task.core.dto.AlertRuleDTO;
import com.datanest.task.core.service.AlertRuleService;
import org.springframework.web.bind.annotation.*;

/**
 * DAG 告警配置快捷入口（Sprint 5，DAG 编辑器工具栏「告警」）。
 * 与全局告警中心操作同一数据源（alert_rule），任何入口修改实时同步。
 * 权限（PRD §8）：查看 = 超管/工程师/治理员；编辑 = 超管/工程师。
 */
@RestController
@RequestMapping("/dev/dags")
public class DagAlertRuleController {

    private final AlertRuleService alertRuleService;

    public DagAlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{dagId}/alert-rule")
    public Result<AlertRuleDTO> getAlertRule(@PathVariable Long dagId) {
        return Result.ok(alertRuleService.getRuleByObject(AlertConstants.OBJECT_TYPE_DAG, dagId));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{dagId}/alert-rule")
    public Result<AlertRuleDTO> updateAlertRule(@PathVariable Long dagId, @RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.upsertRuleByObject(AlertConstants.OBJECT_TYPE_DAG, dagId, dto));
    }
}
