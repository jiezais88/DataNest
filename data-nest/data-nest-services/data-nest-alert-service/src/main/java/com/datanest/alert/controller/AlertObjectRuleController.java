package com.datanest.alert.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.alert.dto.AlertRuleDTO;
import com.datanest.alert.service.AlertRuleService;
import com.datanest.common.model.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 告警对象快捷入口（合并原 DAG / 同步任务 / 采集任务三个快捷 Controller）。
 * 与全局告警中心操作同一数据源（alert_rule），任何入口修改实时同步。
 * 权限（PRD §8）：查看 = 超管/工程师/治理员；编辑 = 超管/工程师。
 */
@RestController
@RequestMapping("/rules/by-object")
public class AlertObjectRuleController {

    private final AlertRuleService alertRuleService;

    public AlertObjectRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    /**
     * 按对象读取告警规则（无规则返回 data = null）。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<AlertRuleDTO> getRuleByObject(@RequestParam String objectType, @RequestParam Long objectId) {
        return Result.ok(alertRuleService.getRuleByObject(objectType, objectId));
    }

    /**
     * 按对象新增或更新告警规则。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping
    public Result<AlertRuleDTO> upsertRuleByObject(@RequestParam String objectType, @RequestParam Long objectId,
                                                   @RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.upsertRuleByObject(objectType, objectId, dto));
    }
}
