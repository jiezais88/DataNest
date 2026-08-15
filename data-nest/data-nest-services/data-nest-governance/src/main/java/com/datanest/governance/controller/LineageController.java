package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.governance.dto.LineageGraphDTO;
import com.datanest.governance.service.LineageService;
import com.datanest.task.core.dto.LineageColumnLinkDTO;
import com.datanest.governance.entity.LineageRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 血缘查询接口
 * Sprint 5：新增表级血缘图谱、字段级血缘、影响/溯源分析。
 */
@Tag(name = "数据血缘", description = "表级血缘图谱 / 字段级血缘 / 影响与溯源分析")
@SaCheckPermission(PermissionCode.METADATA_LINEAGE)
@RestController
@RequestMapping("/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @Operation(summary = "按目标表查血缘记录")
    @GetMapping("/target/{tableName}")
    public Result<List<LineageRecord>> queryByTargetTable(@Parameter(description = "目标表名") @PathVariable String tableName) {
        return Result.ok(lineageService.queryByTargetTable(tableName));
    }

    @Operation(summary = "按 DAG 查血缘记录")
    @GetMapping("/dag/{dagId}")
    public Result<List<LineageRecord>> queryByDagId(@Parameter(description = "DAG ID") @PathVariable Long dagId) {
        return Result.ok(lineageService.queryByDagId(dagId));
    }

    @Operation(summary = "表级血缘图谱", description = "默认一层上下游，depth 扩展层数")
    @GetMapping("/graph")
    public Result<LineageGraphDTO> graph(@Parameter(description = "中心表名") @RequestParam String tableName,
                                         @Parameter(description = "上下游层数") @RequestParam(defaultValue = "1") int depth) {
        return Result.ok(lineageService.buildTableGraph(tableName, depth));
    }

    @Operation(summary = "字段级血缘链路")
    @GetMapping("/columns")
    public Result<List<LineageColumnLinkDTO>> columns(@Parameter(description = "表名") @RequestParam String tableName,
                                                      @Parameter(description = "字段名") @RequestParam String columnName) {
        return Result.ok(lineageService.buildColumnLineage(tableName, columnName));
    }

    @Operation(summary = "影响分析", description = "中心表的下游子图")
    @GetMapping("/impact")
    public Result<LineageGraphDTO> impact(@Parameter(description = "中心表名") @RequestParam String tableName,
                                          @Parameter(description = "下游层数") @RequestParam(defaultValue = "1") int depth) {
        return Result.ok(lineageService.impact(tableName, depth));
    }

    @Operation(summary = "溯源分析", description = "中心表的上游子图")
    @GetMapping("/source")
    public Result<LineageGraphDTO> source(@Parameter(description = "中心表名") @RequestParam String tableName,
                                          @Parameter(description = "上游层数") @RequestParam(defaultValue = "1") int depth) {
        return Result.ok(lineageService.source(tableName, depth));
    }
}
