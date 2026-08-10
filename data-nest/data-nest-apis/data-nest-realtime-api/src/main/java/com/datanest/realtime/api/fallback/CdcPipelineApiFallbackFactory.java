package com.datanest.realtime.api.fallback;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.Result;
import com.datanest.realtime.api.CdcPipelineApi;
import com.datanest.realtime.api.dto.CdcPipelineReferenceDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CdcPipelineApi 熔断降级工厂。
 * <p>
 * 唯一的端点 {@link CdcPipelineApi#listByDatasource} 是删除数据源的前置引用校验，
 * 语义 fail-closed：降级时抛异常阻止删除，避免静默放行误删仍被 CDC 管道引用的数据源
 * （对齐 alert-api 的 listRuleNamesByObject 写法）。
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
        };
    }
}
