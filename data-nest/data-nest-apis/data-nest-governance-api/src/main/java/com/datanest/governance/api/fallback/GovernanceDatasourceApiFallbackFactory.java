package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceDatasourceApi;
import com.datanest.governance.api.dto.AutoCreateCollectTaskRequest;
import com.datanest.governance.api.dto.DatasourceReferencesDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GovernanceDatasourceApi 熔断降级工厂。
 * <p>
 * references → 空 DTO（调用方 fail-closed 兜底在 cascade-delete）；cascade-delete → 抛异常
 * fail-closed（绝不允许"数据源删了元数据残留"静默发生）；auto-create → null（保存主流程不阻断）；
 * by-dag → 0（DAG 删除不阻断，最终一致）。
 */
@Component
public class GovernanceDatasourceApiFallbackFactory implements FallbackFactory<GovernanceDatasourceApi> {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceDatasourceApiFallbackFactory.class);

    @Override
    public GovernanceDatasourceApi create(Throwable cause) {
        logger.warn("GovernanceDatasourceApi 触发熔断降级: {}", cause == null ? "unknown" : cause.getMessage());
        return new GovernanceDatasourceApi() {
            @Override
            public Result<DatasourceReferencesDTO> getReferences(Long id) {
                DatasourceReferencesDTO empty = new DatasourceReferencesDTO();
                empty.setCollectTasks(List.of());
                empty.setMetadataTables(List.of());
                empty.setQualityRules(List.of());
                return Result.ok(empty);
            }

            @Override
            public Result<Void> cascadeDelete(Long id) {
                throw new IllegalStateException(
                        "governance 服务不可用，数据源治理元数据级联删除失败（fail-closed）: datasourceId=" + id, cause);
            }

            @Override
            public Result<Long> autoCreateCollectTask(AutoCreateCollectTaskRequest request) {
                return Result.ok(null);
            }

            @Override
            public Result<Integer> deleteLineageByDag(Long dagId) {
                return Result.ok(0);
            }
        };
    }
}
