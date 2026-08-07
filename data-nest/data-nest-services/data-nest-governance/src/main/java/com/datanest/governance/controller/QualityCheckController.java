package com.datanest.governance.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.governance.service.QualityCheckQueryService;
import com.datanest.task.core.dto.QualityCheckBatchDTO;
import com.datanest.task.core.dto.QualityCheckQueryRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 质量检查执行结果 Controller（Sprint 8 执行层）。
 * <p>
 * 提供批次分页查询与批次详情（含规则明细）。查看权限与质量任务一致（治理员/超管/工程师/分析师）。
 * 微服务化 4.2：查询走 governance 本地 QualityCheckQueryService（task-core 的 QualityCheckService
 * 只保留 worker 执行路径）。
 */
@RestController
@RequestMapping("/quality/checks")
@SaCheckRole(value = {"SUPER_ADMIN", "GOVERNANCE_ADMIN", "DATA_ENGINEER", "DATA_ANALYST"}, mode = SaMode.OR)
public class QualityCheckController {

    private final QualityCheckQueryService checkQueryService;

    public QualityCheckController(QualityCheckQueryService checkQueryService) {
        this.checkQueryService = checkQueryService;
    }

    /**
     * 批次分页列表（按 job / trigger_type / status 过滤）。
     */
    @PostMapping("/page")
    public Result<PageResult<QualityCheckBatchDTO>> page(@RequestBody QualityCheckQueryRequest request) {
        return Result.ok(checkQueryService.listBatches(request));
    }

    /**
     * 批次详情（含规则明细）。
     */
    @GetMapping("/{id}")
    public Result<QualityCheckBatchDTO> getById(@PathVariable Long id) {
        return Result.ok(checkQueryService.getBatchDetail(id));
    }
}
