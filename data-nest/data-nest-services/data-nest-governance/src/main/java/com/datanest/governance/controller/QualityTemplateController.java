package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityRuleTemplateCreateRequest;
import com.datanest.task.core.dto.QualityRuleTemplateDTO;
import com.datanest.task.core.dto.QualityRuleTemplateQueryRequest;
import com.datanest.task.core.dto.QualityRuleTemplateUpdateRequest;
import com.datanest.governance.service.QualityRuleTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 质量规则模板 Controller（Sprint 6 规则模板库，D-D3 决策）。
 * <p>
 * 权限（对照 PRD §8 / 技术文档 §7）：查看为治理员/超管/工程师/分析师；新增/编辑/删除/启停为治理员/超管。
 */
@Tag(name = "质量规则模板", description = "规则模板库维护（内置 + 自定义）")
@RestController
@RequestMapping("/quality/templates")
@SaCheckPermission(PermissionCode.QUALITY_RULE_VIEW)
public class QualityTemplateController {

    private final QualityRuleTemplateService templateService;

    public QualityTemplateController(QualityRuleTemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(summary = "模板列表", description = "含内置，仅启用；供「批量应用」下拉选择等；可按类型过滤")
    @GetMapping
    public Result<List<QualityRuleTemplateDTO>> list(@Parameter(description = "规则类型") @RequestParam(required = false) String type) {
        return Result.ok(templateService.listAll(type));
    }

    @Operation(summary = "模板分页列表")
    @PostMapping("/page")
    public Result<PageResult<QualityRuleTemplateDTO>> page(@RequestBody QualityRuleTemplateQueryRequest request) {
        return Result.ok(templateService.list(request));
    }

    @Operation(summary = "新增自定义模板")
    @PostMapping
    @SaCheckPermission(PermissionCode.QUALITY_RULE_CREATE)
    public Result<QualityRuleTemplateDTO> create(@Valid @RequestBody QualityRuleTemplateCreateRequest request) {
        return Result.ok(templateService.create(request));
    }

    @Operation(summary = "编辑模板", description = "内置/自定义均可编辑")
    @PutMapping("/{id}")
    @SaCheckPermission(PermissionCode.QUALITY_RULE_UPDATE)
    public Result<QualityRuleTemplateDTO> update(@Parameter(description = "模板 ID") @PathVariable Long id,
                                                 @Valid @RequestBody QualityRuleTemplateUpdateRequest request) {
        return Result.ok(templateService.update(id, request));
    }

    @Operation(summary = "删除自定义模板", description = "内置模板不可删除")
    @DeleteMapping("/{id}")
    @SaCheckPermission(PermissionCode.QUALITY_RULE_DELETE)
    public Result<Void> delete(@Parameter(description = "模板 ID") @PathVariable Long id) {
        templateService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "启停模板")
    @PostMapping("/{id}/toggle")
    @SaCheckPermission(PermissionCode.QUALITY_RULE_UPDATE)
    public Result<QualityRuleTemplateDTO> toggle(@Parameter(description = "模板 ID") @PathVariable Long id,
                                                 @Parameter(description = "目标启用状态（不传则取反）") @RequestParam(required = false) Boolean enabled) {
        return Result.ok(templateService.toggle(id, enabled));
    }
}
