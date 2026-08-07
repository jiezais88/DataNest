package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.QualityExecutionApi;
import com.datanest.governance.api.dto.QualityBatchCreateRequest;
import com.datanest.governance.api.dto.QualityBatchFinishRequest;
import com.datanest.governance.api.dto.QualityBatchInfoDTO;
import com.datanest.governance.api.dto.QualityDetailCreateRequest;
import com.datanest.governance.api.dto.QualityExecutionPlanDTO;
import com.datanest.governance.api.dto.QualityExecutionPlanRequest;
import com.datanest.governance.api.dto.QualityRulePlanRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * QualityExecutionApi 熔断降级工厂。
 * <p>
 * 查询类（计划装配/批次信息）降级为 null；写类（批次/明细/收尾）降级为空操作，
 * 均不阻断主流程（执行失败由 worker 侧按既有失败路径处理）。
 */
@Component
public class QualityExecutionApiFallbackFactory implements FallbackFactory<QualityExecutionApi> {

    private static final Logger logger = LoggerFactory.getLogger(QualityExecutionApiFallbackFactory.class);

    @Override
    public QualityExecutionApi create(Throwable cause) {
        logger.warn("QualityExecutionApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new QualityExecutionApi() {
            @Override
            public Result<QualityExecutionPlanDTO> plan(QualityExecutionPlanRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<QualityExecutionPlanDTO> planByRule(QualityRulePlanRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> createBatch(QualityBatchCreateRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Long> saveDetail(Long id, QualityDetailCreateRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> finishBatch(Long id, QualityBatchFinishRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<QualityBatchInfoDTO> batchInfo(Long id) {
                return Result.ok(null);
            }
        };
    }
}
