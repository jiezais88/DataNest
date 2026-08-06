package com.datanest.alert.api.fallback;

import com.datanest.alert.api.AlertApi;
import com.datanest.alert.api.dto.AlertFireBatchRequest;
import com.datanest.alert.api.dto.AlertFireRequest;
import com.datanest.alert.api.dto.AlertHistoryDTO;
import com.datanest.alert.api.dto.DagAlertConfigInfo;
import com.datanest.alert.api.dto.DagFinishedRequest;
import com.datanest.alert.api.dto.DagNodeTimeoutRequest;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AlertApi 熔断降级工厂。
 * <p>
 * 告警链路整体 fail-open（不阻断主流程）：触发/通知类降级为「未发送/空操作」，查询类降级为空集；
 * 仅 {@link AlertApi#listRuleNamesByObject} 删除前置引用校验 fail-closed，降级时抛异常阻止删除，
 * 避免静默放行误删仍被告警规则引用的对象。
 */
@Component
public class AlertApiFallbackFactory implements FallbackFactory<AlertApi> {

    private static final Logger logger = LoggerFactory.getLogger(AlertApiFallbackFactory.class);

    @Override
    public AlertApi create(Throwable cause) {
        logger.warn("AlertApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new AlertApi() {
            @Override
            public Result<Boolean> fire(AlertFireRequest request) {
                return Result.ok(false);
            }

            @Override
            public Result<Boolean> fireBatch(AlertFireBatchRequest request) {
                return Result.ok(false);
            }

            @Override
            public Result<Void> dagFinished(DagFinishedRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> dagNodeTimeout(DagNodeTimeoutRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> deleteRuleByObject(String objectType, Long objectId) {
                return Result.ok(null);
            }

            @Override
            public Result<List<String>> listRuleNamesByObject(String objectType, Long objectId) {
                // fail-closed：删除前置引用校验不可静默放行
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "告警服务不可用，删除前置校验失败");
            }

            @Override
            public Result<List<AlertHistoryDTO>> listByQualityBatch(Long batchId) {
                return Result.ok(List.of());
            }

            @Override
            public Result<Integer> cleanupHistories(int beforeDays) {
                return Result.ok(0);
            }

            @Override
            public Result<DagAlertConfigInfo> resolveDagAlertConfig(Long dagId) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> deleteDagAlertConfigByDag(Long dagId) {
                return Result.ok(null);
            }

            @Override
            public Result<Void> deleteDagAlertHistoriesByExecutions(List<Long> executionIds) {
                return Result.ok(null);
            }
        };
    }
}
