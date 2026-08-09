package com.datanest.governance.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.QualityBatchCreateRequest;
import com.datanest.governance.api.dto.QualityBatchFinishRequest;
import com.datanest.governance.api.dto.QualityBatchInfoDTO;
import com.datanest.governance.api.dto.QualityDetailCreateRequest;
import com.datanest.governance.api.dto.QualityExecutionPlanDTO;
import com.datanest.governance.api.dto.QualityExecutionPlanRequest;
import com.datanest.governance.api.dto.QualityRulePlanRequest;
import com.datanest.governance.service.internal.QualityExecutionService;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * 质量执行域内部接口（实现 governance-api 的 QualityExecutionApi 契约）。
 * <p>
 * 仅供服务间内部调用（worker 执行质量检查：计划装配、批次/明细落库、批次收尾、超时回调查询），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal")
public class QualityExecutionController {

    private final QualityExecutionService qualityExecutionService;

    public QualityExecutionController(QualityExecutionService qualityExecutionService) {
        this.qualityExecutionService = qualityExecutionService;
    }

    /**
     * 按任务装配执行计划（任务 + 启用规则 + 模板 + 元数据表 + 执行 SQL），规则空列表合法。
     */
    @PostMapping("/quality/execution/plan")
    public Result<QualityExecutionPlanDTO> plan(@RequestBody QualityExecutionPlanRequest request) {
        return Result.ok(qualityExecutionService.buildPlan(request.getJobId()));
    }

    /**
     * 按单规则装配执行计划（executeRule 路径，jobId 可空）。
     */
    @PostMapping("/quality/execution/plan-by-rule")
    public Result<QualityExecutionPlanDTO> planByRule(@RequestBody QualityRulePlanRequest request) {
        return Result.ok(qualityExecutionService.buildPlanByRule(request.getRuleId()));
    }

    /**
     * 初始化 RUNNING 批次，返回批次 ID。
     */
    @PostMapping("/quality/batches")
    public Result<Long> createBatch(@RequestBody QualityBatchCreateRequest request) {
        return Result.ok(qualityExecutionService.createBatch(request));
    }

    /**
     * 单条明细落库；单规则批次时顺带把 batch.jobName 更新为「规则名（表名）」。
     */
    @PostMapping("/quality/batches/{id}/details")
    public Result<Long> saveDetail(@PathVariable Long id, @RequestBody QualityDetailCreateRequest request) {
        return Result.ok(qualityExecutionService.saveDetail(id, request));
    }

    /**
     * 批次收尾：终态回写 + last_trigger_at 更新 + 评分重算 + 合并告警（fireBatchAlert）。
     */
    @PostMapping("/quality/batches/{id}/finish")
    public Result<Void> finishBatch(@PathVariable Long id, @RequestBody QualityBatchFinishRequest request) {
        qualityExecutionService.finishBatch(id, request);
        return Result.ok(null);
    }

    /**
     * 批次信息查询（超时回调 checkAndFireTimeout 用）。
     */
    @GetMapping("/quality/batches/{id}")
    public Result<QualityBatchInfoDTO> batchInfo(@PathVariable Long id) {
        return Result.ok(qualityExecutionService.batchInfo(id));
    }
}
