package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityRuleBatchCreateRequest;
import com.datanest.task.core.dto.QualityRuleCreateRequest;
import com.datanest.task.core.dto.QualityRuleDTO;
import com.datanest.task.core.dto.QualityRuleQueryRequest;
import com.datanest.task.core.dto.QualityRuleUpdateRequest;
import com.datanest.governance.dto.QualityPythonScriptTestRequest;
import com.datanest.governance.dto.QualityPythonScriptTestResponse;
import com.datanest.governance.dto.QualitySqlPreviewExecuteRequest;
import com.datanest.governance.dto.QualitySqlPreviewExecuteResponse;
import com.datanest.governance.service.QualityRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 质量规则 Controller（Sprint 6 配置层）。
 * <p>
 * 权限（对照 PRD §8 / 技术文档 §7）：查看为治理员/超管/工程师/分析师；新增/编辑/删除/启停/执行为治理员/超管。
 */
@Tag(name = "质量规则", description = "质量规则 CRUD / 模板批量应用 / SQL 预览与脚本试跑")
@RestController
@RequestMapping("/quality/rules")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityRuleController {

    private final QualityRuleService ruleService;

    public QualityRuleController(QualityRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Operation(summary = "规则分页查询", description = "规则独立菜单，支持关键字/类型/状态/所属任务筛选")
    @PostMapping("/page")
    public Result<PageResult<QualityRuleDTO>> page(@RequestBody QualityRuleQueryRequest request) {
        return Result.ok(ruleService.page(request));
    }

    @Operation(summary = "按任务查规则列表")
    @GetMapping("/by-job/{jobId}")
    public Result<List<QualityRuleDTO>> listByJob(@Parameter(description = "质量任务 ID") @PathVariable Long jobId) {
        return Result.ok(ruleService.listByJob(jobId));
    }

    @Operation(summary = "规则详情")
    @GetMapping("/{id}")
    public Result<QualityRuleDTO> getById(@Parameter(description = "规则 ID") @PathVariable Long id) {
        return Result.ok(ruleService.getById(id));
    }

    @Operation(summary = "任务下新增规则")
    @PostMapping
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleDTO> create(@Valid @RequestBody QualityRuleCreateRequest request) {
        return Result.ok(ruleService.create(request));
    }

    @Operation(summary = "模板批量应用", description = "选「1 个模板 + 多张表」，逐表生成规则实例（逐表可微调）")
    @PostMapping("/batch")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<List<QualityRuleDTO>> batch(@Valid @RequestBody QualityRuleBatchCreateRequest request) {
        return Result.ok(ruleService.batchCreate(request));
    }

    @Operation(summary = "编辑规则")
    @PutMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleDTO> update(@Parameter(description = "规则 ID") @PathVariable Long id,
                                         @Valid @RequestBody QualityRuleUpdateRequest request) {
        return Result.ok(ruleService.update(id, request));
    }

    @Operation(summary = "删除规则")
    @DeleteMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> delete(@Parameter(description = "规则 ID") @PathVariable Long id) {
        ruleService.delete(id);
        return Result.ok(null);
    }

    @Operation(summary = "启停规则")
    @PostMapping("/{id}/toggle")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleDTO> toggle(@Parameter(description = "规则 ID") @PathVariable Long id,
                                         @Parameter(description = "目标启用状态（不传则取反）") @RequestParam(required = false) Boolean enabled) {
        return Result.ok(ruleService.toggle(id, enabled));
    }

    @Operation(summary = "单条规则执行")
    @PostMapping("/{id}/execute")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> execute(@Parameter(description = "规则 ID") @PathVariable Long id) {
        ruleService.executeRule(id);
        return Result.ok(null);
    }

    @Operation(summary = "预览规则执行 SQL", description = "模板占位展开结果")
    @GetMapping("/{id}/preview-sql")
    public Result<String> previewSql(@Parameter(description = "规则 ID") @PathVariable Long id) {
        return Result.ok(ruleService.previewSql(id));
    }

    @Operation(summary = "试跑 PYTHON 规则脚本", description = "保存前验证脚本并查看返回 dict（Sprint 7 DG-10）")
    @PostMapping("/test-script")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityPythonScriptTestResponse> testScript(@Valid @RequestBody QualityPythonScriptTestRequest request) {
        return Result.ok(ruleService.testPythonScript(request));
    }

    @Operation(summary = "CUSTOM_SQL 规则执行预览", description = "真实执行返回列清单 + 样例行，供多指标选择 resultMetric（Sprint 7 DG-10）")
    @PostMapping("/preview-execute")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualitySqlPreviewExecuteResponse> previewExecute(@Valid @RequestBody QualitySqlPreviewExecuteRequest request) {
        return Result.ok(ruleService.previewExecuteSql(request));
    }
}
