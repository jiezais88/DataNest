package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.QualityBatchCreateRequest;
import com.datanest.governance.api.dto.QualityBatchFinishRequest;
import com.datanest.governance.api.dto.QualityBatchInfoDTO;
import com.datanest.governance.api.dto.QualityDetailCreateRequest;
import com.datanest.governance.api.dto.QualityExecutionPlanDTO;
import com.datanest.governance.api.dto.QualityExecutionPlanRequest;
import com.datanest.governance.api.dto.QualityRulePlanRequest;
import com.datanest.governance.api.fallback.QualityExecutionApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 质量执行域内部 Feign 契约（微服务化 4.1）。
 * <p>
 * 仅供服务间内部调用，对应 data-nest-governance 的 /governance/internal/** 端点。
 * 执行计划装配、批次/明细落库、批次收尾串联（评分重算 + 合并告警）均在服务端完成，
 * worker 只负责执行 SQL 与阈值判定。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "qualityExecutionApi",
        fallbackFactory = QualityExecutionApiFallbackFactory.class)
public interface QualityExecutionApi {

    /** 按任务装配执行计划（任务 + 启用规则 + 模板 + 元数据表 + 执行 SQL） */
    @PostMapping("/quality/execution/plan")
    Result<QualityExecutionPlanDTO> plan(@RequestBody QualityExecutionPlanRequest request);

    /** 按单规则装配执行计划（executeRule 路径，jobId 可空） */
    @PostMapping("/quality/execution/plan-by-rule")
    Result<QualityExecutionPlanDTO> planByRule(@RequestBody QualityRulePlanRequest request);

    /** 初始化 RUNNING 批次，返回批次 ID */
    @PostMapping("/quality/batches")
    Result<Long> createBatch(@RequestBody QualityBatchCreateRequest request);

    /** 单条明细落库（单规则批次时顺带更新 batch.jobName 为 规则名（表名）），返回明细 ID */
    @PostMapping("/quality/batches/{id}/details")
    Result<Long> saveDetail(@PathVariable("id") Long id, @RequestBody QualityDetailCreateRequest request);

    /** 批次收尾：终态回写 + last_trigger_at + 评分重算 + 合并告警 */
    @PostMapping("/quality/batches/{id}/finish")
    Result<Void> finishBatch(@PathVariable("id") Long id, @RequestBody QualityBatchFinishRequest request);

    /** 批次信息查询（超时回调 checkAndFireTimeout 用） */
    @GetMapping("/quality/batches/{id}")
    Result<QualityBatchInfoDTO> batchInfo(@PathVariable("id") Long id);
}
