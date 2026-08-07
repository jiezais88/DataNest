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
import com.datanest.governance.service.QualityRuleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 质量规则 Controller（Sprint 6 配置层）。
 * <p>
 * 权限（对照 PRD §8 / 技术文档 §7）：查看为治理员/超管/工程师/分析师；新增/编辑/删除/启停/执行为治理员/超管。
 */
@RestController
@RequestMapping("/quality/rules")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityRuleController {

    private final QualityRuleService ruleService;

    public QualityRuleController(QualityRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * 分页查询规则（Sprint 7 规则独立菜单，支持关键字/类型/状态/所属任务筛选）。
     */
    @PostMapping("/page")
    public Result<PageResult<QualityRuleDTO>> page(@RequestBody QualityRuleQueryRequest request) {
        return Result.ok(ruleService.page(request));
    }

    /**
     * 按任务查规则列表。
     */
    @GetMapping("/by-job/{jobId}")
    public Result<List<QualityRuleDTO>> listByJob(@PathVariable Long jobId) {
        return Result.ok(ruleService.listByJob(jobId));
    }

    /**
     * 规则详情。
     */
    @GetMapping("/{id}")
    public Result<QualityRuleDTO> getById(@PathVariable Long id) {
        return Result.ok(ruleService.getById(id));
    }

    /**
     * 任务下新增规则。
     */
    @PostMapping
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleDTO> create(@Valid @RequestBody QualityRuleCreateRequest request) {
        return Result.ok(ruleService.create(request));
    }

    /**
     * 模板批量应用：选「1 个模板 + 多张表」，逐表生成规则实例（逐表可微调）。
     */
    @PostMapping("/batch")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<List<QualityRuleDTO>> batch(@Valid @RequestBody QualityRuleBatchCreateRequest request) {
        return Result.ok(ruleService.batchCreate(request));
    }

    /**
     * 编辑规则。
     */
    @PutMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleDTO> update(@PathVariable Long id,
                                         @Valid @RequestBody QualityRuleUpdateRequest request) {
        return Result.ok(ruleService.update(id, request));
    }

    /**
     * 删除规则。
     */
    @DeleteMapping("/{id}")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return Result.ok(null);
    }

    /**
     * 启停规则。
     */
    @PostMapping("/{id}/toggle")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<QualityRuleDTO> toggle(@PathVariable Long id,
                                         @RequestParam(required = false) Boolean enabled) {
        return Result.ok(ruleService.toggle(id, enabled));
    }

    /**
     * 单条规则执行（预留：执行校验下一批实现）。
     */
    @PostMapping("/{id}/execute")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> execute(@PathVariable Long id) {
        ruleService.executeRule(id);
        return Result.ok(null);
    }

    /**
     * 预览规则执行 SQL（模板占位展开结果）。
     */
    @GetMapping("/{id}/preview-sql")
    public Result<String> previewSql(@PathVariable Long id) {
        return Result.ok(ruleService.previewSql(id));
    }
}
