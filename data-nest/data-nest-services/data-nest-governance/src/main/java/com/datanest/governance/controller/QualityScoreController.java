package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityScoreConfigDTO;
import com.datanest.task.core.dto.QualityScoreDTO;
import com.datanest.task.core.dto.QualityScoreQueryRequest;
import com.datanest.task.core.dto.QualityTableRuleResultDTO;
import com.datanest.governance.service.QualityScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 表级质量评分 Controller（Sprint 6 NG8）。
 * <p>
 * 提供单表评分、批量评分（血缘图谱回填用）、评分列表分页三接口，
 * 以及元数据「质量」页签的按表规则最近结果查询、按表执行全部启用规则、全局扣分配置读写。
 * 查看权限与质量任务一致（治理员/超管/工程师/分析师）；执行与扣分配置写为治理员/超管。
 */
@Tag(name = "质量评分", description = "表级质量评分查询与全局扣分配置")
@RestController
@RequestMapping("/quality/scores")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityScoreController {

    private final QualityScoreService scoreService;

    public QualityScoreController(QualityScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @Operation(summary = "单表评分")
    @GetMapping("/table/{tableId}")
    public Result<QualityScoreDTO> getByTableId(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(scoreService.getByTableId(tableId));
    }

    @Operation(summary = "批量查多表评分", description = "血缘回填用；传表名集合，返回命中列表")
    @PostMapping("/by-tables")
    public Result<List<QualityScoreDTO>> byTables(@RequestBody List<String> tableNames) {
        return Result.ok(scoreService.listByTableNames(tableNames));
    }

    @Operation(summary = "评分列表分页", description = "按关键字/数据源/健康度筛选")
    @PostMapping("/page")
    public Result<PageResult<QualityScoreDTO>> page(@RequestBody QualityScoreQueryRequest request) {
        return Result.ok(scoreService.listPage(request));
    }

    @Operation(summary = "按表查规则最近结果", description = "该表所有启用规则 + 最近一次检查结果（元数据「质量」页签规则结果列表）")
    @GetMapping("/table/{tableId}/rules")
    public Result<List<QualityTableRuleResultDTO>> tableRules(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        return Result.ok(scoreService.listTableRuleResults(tableId));
    }

    @Operation(summary = "按表执行全部启用规则", description = "异步投递 worker，逐条触发")
    @PostMapping("/table/{tableId}/execute")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> executeTable(@Parameter(description = "表 ID") @PathVariable Long tableId) {
        scoreService.executeTableRules(tableId);
        return Result.ok(null);
    }

    @Operation(summary = "读全局扣分配置", description = "「扣分配置」弹窗回显")
    @GetMapping("/config")
    public Result<QualityScoreConfigDTO> getConfig() {
        return Result.ok(scoreService.getConfig());
    }

    @Operation(summary = "更新全局扣分配置", description = "保存后 ScoreCalculator 动态生效，无需重启")
    @PutMapping("/config")
    @SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN"}, mode = SaMode.OR)
    public Result<Void> updateConfig(@RequestBody QualityScoreConfigDTO dto) {
        scoreService.updateConfig(dto);
        return Result.ok(null);
    }
}
