package com.datanest.alert.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.alert.dto.AlertObjectOptionDTO;
import com.datanest.alert.dto.AlertRuleDTO;
import com.datanest.alert.service.AlertRuleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 全局告警中心 - 告警规则 CRUD（Sprint 5）。
 * 权限（PRD §8）：查看 = 超管/工程师/治理员；新增/编辑/停用 = 超管/工程师。
 */
@RestController
@RequestMapping("/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    public AlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping
    public Result<PageResult<AlertRuleDTO>> list(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int pageSize,
                                                 @RequestParam(required = false) String objectType,
                                                 @RequestParam(required = false) String keyword) {
        return Result.ok(alertRuleService.listRules(page, pageSize, objectType, keyword));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public Result<AlertRuleDTO> get(@PathVariable Long id) {
        return Result.ok(alertRuleService.getRule(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PostMapping
    public Result<AlertRuleDTO> create(@RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.createRule(dto));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}")
    public Result<AlertRuleDTO> update(@PathVariable Long id, @RequestBody AlertRuleDTO dto) {
        return Result.ok(alertRuleService.updateRule(id, dto));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        alertRuleService.deleteRule(id);
        return Result.<Void>ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Boolean enabled) {
        alertRuleService.toggleRule(id, enabled);
        return Result.<Void>ok(null);
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/{id}/users")
    public Result<List<Long>> getUsers(@PathVariable Long id) {
        return Result.ok(alertRuleService.getRuleUsers(id));
    }

    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER"}, mode = SaMode.OR)
    @PutMapping("/{id}/users")
    public Result<Void> setUsers(@PathVariable Long id, @RequestBody List<Long> userIds) {
        alertRuleService.setRuleUsers(id, userIds);
        return Result.<Void>ok(null);
    }

    /**
     * 新增规则时可选对象下拉（按对象类型返回 id + name）。
     */
    @SaCheckRole(value = {"SUPER_ADMIN", "DATA_ENGINEER", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    @GetMapping("/object-options")
    public Result<List<AlertObjectOptionDTO>> objectOptions(@RequestParam String objectType) {
        return Result.ok(alertRuleService.listObjectOptions(objectType));
    }
}
