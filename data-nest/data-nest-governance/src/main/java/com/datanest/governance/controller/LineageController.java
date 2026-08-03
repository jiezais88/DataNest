package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.LineageGraphDTO;
import com.datanest.governance.service.LineageService;
import com.datanest.task.core.dto.LineageColumnLinkDTO;
import com.datanest.task.core.entity.LineageRecord;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 血缘查询接口
 * Sprint 5：新增表级血缘图谱、字段级血缘、影响/溯源分析。
 */
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
@RestController
@RequestMapping("/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @GetMapping("/target/{tableName}")
    public Result<List<LineageRecord>> queryByTargetTable(@PathVariable String tableName) {
        return Result.ok(lineageService.queryByTargetTable(tableName));
    }

    @GetMapping("/dag/{dagId}")
    public Result<List<LineageRecord>> queryByDagId(@PathVariable Long dagId) {
        return Result.ok(lineageService.queryByDagId(dagId));
    }

    /**
     * 表级血缘图谱（默认一层上下游，depth 扩展层数）。
     */
    @GetMapping("/graph")
    public Result<LineageGraphDTO> graph(@RequestParam String tableName,
                                         @RequestParam(defaultValue = "1") int depth) {
        return Result.ok(lineageService.buildTableGraph(tableName, depth));
    }

    /**
     * 字段级血缘链路。
     */
    @GetMapping("/columns")
    public Result<List<LineageColumnLinkDTO>> columns(@RequestParam String tableName,
                                                      @RequestParam String columnName) {
        return Result.ok(lineageService.buildColumnLineage(tableName, columnName));
    }

    /**
     * 影响分析：中心表的下游子图。
     */
    @GetMapping("/impact")
    public Result<LineageGraphDTO> impact(@RequestParam String tableName,
                                          @RequestParam(defaultValue = "1") int depth) {
        return Result.ok(lineageService.impact(tableName, depth));
    }

    /**
     * 溯源分析：中心表的上游子图。
     */
    @GetMapping("/source")
    public Result<LineageGraphDTO> source(@RequestParam String tableName,
                                          @RequestParam(defaultValue = "1") int depth) {
        return Result.ok(lineageService.source(tableName, depth));
    }
}
