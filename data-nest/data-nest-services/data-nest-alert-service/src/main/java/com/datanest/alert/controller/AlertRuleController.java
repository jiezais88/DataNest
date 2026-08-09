package com.datanest.alert.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.alert.dto.AlertObjectOptionDTO;
import com.datanest.alert.dto.AlertRuleDTO;
import com.datanest.alert.service.AlertRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 全局告警中心 - 告警规则 CRUD（Sprint 5）。
 * 权限（PRD §8）：查看 = 超管/工程师/治理员；新增/编辑/停用 = 超管/工程师。
 */
@Tag(name = "告警规则", description = "全局告警规则 CRUD / 启停 / 接收人管理")
@RestController
@RequestMapping("/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    public AlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @Operation(summary = "告警规则分页列表")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<AlertRuleDTO>> list(@Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
                                                 @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") int pageSize,
                                                 @Parameter(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY）") @RequestParam(required = false) String objectType,
                                                 @Parameter(description = "关键字（模糊匹配）") @RequestParam(required = false) String keyword) {
        return Result.ok(alertRuleService.listRules(page, pageSize, objectType, keyword));
    }

    @Operation(summary = "告警规则详情")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<AlertRuleDTO> get(@Parameter(description = "规则 ID") @PathVariable Long id) {
        return Result.ok(alertRuleService.getRule(id));
    }

    @Operation(summary = "创建告警规则")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<AlertRuleDTO> create(@RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.createRule(dto));
    }

    @Operation(summary = "编辑告警规则")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<AlertRuleDTO> update(@Parameter(description = "规则 ID") @PathVariable Long id, @RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.updateRule(id, dto));
    }

    @Operation(summary = "删除告警规则")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "规则 ID") @PathVariable Long id) {
        alertRuleService.deleteRule(id);
        return Result.<Void>ok(null);
    }

    @Operation(summary = "切换启用/停用")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@Parameter(description = "规则 ID") @PathVariable Long id,
                               @Parameter(description = "是否启用") @RequestParam Boolean enabled) {
        alertRuleService.toggleRule(id, enabled);
        return Result.<Void>ok(null);
    }

    @Operation(summary = "查询规则接收人")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/users")
    public Result<List<Long>> getUsers(@Parameter(description = "规则 ID") @PathVariable Long id) {
        return Result.ok(alertRuleService.getRuleUsers(id));
    }

    @Operation(summary = "设置规则接收人")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}/users")
    public Result<Void> setUsers(@Parameter(description = "规则 ID") @PathVariable Long id, @RequestBody List<Long> userIds) {
        alertRuleService.setRuleUsers(id, userIds);
        return Result.<Void>ok(null);
    }

    @Operation(summary = "可选告警对象下拉", description = "按对象类型返回 id + name 选项，新增规则时使用")
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/object-options")
    public Result<List<AlertObjectOptionDTO>> objectOptions(@Parameter(description = "告警对象类型（DAG/SYNC_JOB/COLLECT_TASK/QUALITY）") @RequestParam String objectType) {
        return Result.ok(alertRuleService.listObjectOptions(objectType));
    }
}
