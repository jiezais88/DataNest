package com.datanest.alert.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.alert.dto.AlertRuleDTO;
import com.datanest.common.auth.PermissionCode;
import com.datanest.alert.service.AlertRuleService;
import com.datanest.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 告警对象快捷入口（合并原 DAG / 同步任务 / 采集任务三个快捷 Controller）。
 * 与全局告警中心操作同一数据源（alert_rule），任何入口修改实时同步。
 * 权限（PRD §8）：查看 = 超管/工程师/治理员；编辑 = 超管/工程师。
 */
@Tag(name = "告警对象快捷入口", description = "按告警对象读取/新增/更新告警规则，与全局告警中心同源")
@RestController
@RequestMapping("/rules/by-object")
public class AlertObjectRuleController {

    private final AlertRuleService alertRuleService;

    public AlertObjectRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @Operation(summary = "按对象读取告警规则", description = "无规则时返回 data = null")
    @SaCheckPermission(PermissionCode.ALERT_VIEW)
    @GetMapping
    public Result<AlertRuleDTO> getRuleByObject(@Parameter(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY）") @RequestParam String objectType,
                                                @Parameter(description = "告警对象 ID") @RequestParam Long objectId) {
        return Result.ok(alertRuleService.getRuleByObject(objectType, objectId));
    }

    @Operation(summary = "按对象新增或更新告警规则")
    @SaCheckPermission(PermissionCode.ALERT_RULE_MANAGE)
    @PutMapping
    public Result<AlertRuleDTO> upsertRuleByObject(@Parameter(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY）") @RequestParam String objectType,
                                                   @Parameter(description = "告警对象 ID") @RequestParam Long objectId,
                                                   @RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.upsertRuleByObject(objectType, objectId, dto));
    }
}
