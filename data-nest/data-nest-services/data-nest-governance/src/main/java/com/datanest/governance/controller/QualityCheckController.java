package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.task.core.dto.QualityCheckBatchDTO;
import com.datanest.task.core.dto.QualityCheckQueryRequest;
import com.datanest.task.core.service.QualityCheckService;
import org.springframework.web.bind.annotation.*;

/**
 * 质量检查执行结果 Controller（Sprint 8 执行层）。
 * <p>
 * 提供批次分页查询与批次详情（含规则明细）。查看权限与质量任务一致（治理员/超管/工程师/分析师）。
 */
@RestController
@RequestMapping("/quality/checks")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityCheckController {

    private final QualityCheckService checkService;

    public QualityCheckController(QualityCheckService checkService) {
        this.checkService = checkService;
    }

    /**
     * 批次分页列表（按 job / trigger_type / status 过滤）。
     */
    @PostMapping("/page")
    public Result<PageResult<QualityCheckBatchDTO>> page(@RequestBody QualityCheckQueryRequest request) {
        return Result.ok(checkService.listBatches(request));
    }

    /**
     * 批次详情（含规则明细）。
     */
    @GetMapping("/{id}")
    public Result<QualityCheckBatchDTO> getById(@PathVariable Long id) {
        return Result.ok(checkService.getBatchDetail(id));
    }
}
