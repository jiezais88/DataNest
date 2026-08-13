package com.datanest.realtime.api.fallback;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcPipelineApi;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import com.datanest.realtime.api.dto.CdcPipelineSubscribeDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CdcPipelineApi 熔断降级工厂。
 * <p>
 * {@link CdcPipelineApi#listByDatasource} 是删除数据源的前置引用校验，语义 fail-closed：
 * 降级时抛异常阻止删除，避免静默放行误删仍被 CDC 管道引用的数据源（对齐 alert-api 的 listRuleNamesByObject 写法）；
 * {@link CdcPipelineApi#names} 是读路径对象名反查（Sprint 9 F3），语义 fail-open：降级空 Map 不阻断。
 */
@Component
public class CdcPipelineApiFallbackFactory implements FallbackFactory<CdcPipelineApi> {

    private static final Logger logger = LoggerFactory.getLogger(CdcPipelineApiFallbackFactory.class);

    @Override
    public CdcPipelineApi create(Throwable cause) {
        logger.warn("CdcPipelineApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new CdcPipelineApi() {
            @Override
            public Result<List<CdcPipelineReferenceDTO>> listByDatasource(Long datasourceId) {
                // fail-closed：删除前置引用校验不可静默放行
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "实时服务不可用，删除前置校验失败");
            }

            @Override
            public Result<Map<Long, String>> names(List<Long> ids) {
                // fail-open：对象名反查降级空 Map（告警触发/展示不受阻断）
                return Result.ok(Collections.emptyMap());
            }

            @Override
            public Result<Boolean> refreshCatalogIfRunning() {
                // 定时调度场景：抛异常本轮跳过，下轮调度再来（对齐 job handler 的 RemoteCalls 容错语义）
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "实时服务不可用，本轮湖仓 catalog 自动刷新跳过");
            }

            @Override
            public Result<CdcPipelineSubscribeDTO> getSubscribeInfo(Long id) {
                // fail-closed：订阅校验需确认管道 RUNNING 状态，实时服务不可达时拒绝订阅（防订阅到未知状态管道）
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "实时服务不可用，订阅校验失败");
            }
        };
    }
}
