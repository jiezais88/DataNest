package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityRuleTemplateCreateRequest;
import com.datanest.task.core.dto.QualityRuleTemplateDTO;
import com.datanest.task.core.dto.QualityRuleTemplateQueryRequest;
import com.datanest.task.core.dto.QualityRuleTemplateUpdateRequest;
import com.datanest.governance.service.QualityRuleTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 质量规则模板 Controller（Sprint 6 规则模板库，D-D3 决策）。
 * <p>
 * 权限（对照 PRD §8 / 技术文档 §7）：查看为治理员/超管/工程师/分析师；新增/编辑/删除/启停为治理员/超管。
 */
@RestController
@RequestMapping("/quality/templates")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityTemplateController {

    private final QualityRuleTemplateService templateService;

    public QualityTemplateController(QualityRuleTemplateService templateService) {
        this.templateService = templateService;
    }

    /**
     * 模板列表（含内置，仅启用；供「批量应用」下拉选择等）。可按类型过滤。
     */
    @GetMapping
    public Result<List<QualityRuleTemplateDTO>> list(@RequestParam(required = false) String type) {
        return Result.ok(templateService.listAll(type));
    }

    /**
     * 分页列表。
     */
    @PostMapping("/page")
    public Result<PageResult<QualityRuleTemplateDTO>> page(@RequestBody QualityRuleTemplateQueryRequest request) {
        return Result.ok(templateService.list(request));
    }

    /**
     * 新增自定义模板。
     */
    @PostMapping
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleTemplateDTO> create(@Valid @RequestBody QualityRuleTemplateCreateRequest request) {
        return Result.ok(templateService.create(request));
    }

    /**
     * 编辑模板（内置/自定义均可编辑）。
     */
    @PutMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleTemplateDTO> update(@PathVariable Long id,
                                                 @Valid @RequestBody QualityRuleTemplateUpdateRequest request) {
        return Result.ok(templateService.update(id, request));
    }

    /**
     * 删除自定义模板（内置模板不可删除）。
     */
    @DeleteMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return Result.ok(null);
    }

    /**
     * 启停模板。
     */
    @PostMapping("/{id}/toggle")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleTemplateDTO> toggle(@PathVariable Long id,
                                                 @RequestParam(required = false) Boolean enabled) {
        return Result.ok(templateService.toggle(id, enabled));
    }
}
