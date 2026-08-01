package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.Result;
import com.datanest.governance.service.LineageService;
import com.datanest.task.core.entity.LineageRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 血缘查询接口
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
}
