package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityScoreDTO;
import com.datanest.task.core.dto.QualityScoreQueryRequest;
import com.datanest.task.core.service.QualityScoreService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 表级质量评分 Controller（Sprint 6 NG8）。
 * <p>
 * 提供单表评分、批量评分（血缘图谱回填用）、评分列表分页三接口。
 * 查看权限与质量任务一致（治理员/超管/工程师/分析师）。
 */
@RestController
@RequestMapping("/quality/scores")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityScoreController {

    private final QualityScoreService scoreService;

    public QualityScoreController(QualityScoreService scoreService) {
        this.scoreService = scoreService;
    }

    /** 单表评分。 */
    @GetMapping("/table/{tableId}")
    public Result<QualityScoreDTO> getByTableId(@PathVariable Long tableId) {
        return Result.ok(scoreService.getByTableId(tableId));
    }

    /** 批量查多表评分（血缘回填用），传表名集合，返回命中列表。 */
    @PostMapping("/by-tables")
    public Result<List<QualityScoreDTO>> byTables(@RequestBody List<String> tableNames) {
        return Result.ok(scoreService.listByTableNames(tableNames));
    }

    /** 评分列表分页（按关键字/数据源/健康度筛选）。 */
    @PostMapping("/page")
    public Result<PageResult<QualityScoreDTO>> page(@RequestBody QualityScoreQueryRequest request) {
        return Result.ok(scoreService.listPage(request));
    }
}
