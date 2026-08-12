package com.datanest.governance.api.fallback;

import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceMetadataApi;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GovernanceMetadataApi 熔断降级工厂（Sprint 10 F1，fail-closed 语义）。
 * <p>
 * 治理服务不可达时返回 null（区别于正常查询返回空列表）：
 * 数据服务检测到 null 即拒绝 SQL 执行/API 创建并提示「分级服务暂不可用」（用户已确认 fail-closed，
 * 见技术文档 §8 Blocker 3），避免机密表因治理服务故障而裸奔。
 */
@Component
public class GovernanceMetadataApiFallbackFactory implements FallbackFactory<GovernanceMetadataApi> {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceMetadataApiFallbackFactory.class);

    @Override
    public GovernanceMetadataApi create(Throwable cause) {
        logger.warn("GovernanceMetadataApi 触发熔断降级（fail-closed）: {}",
                cause == null ? "unknown" : cause.getMessage());
        return new GovernanceMetadataApi() {
            @Override
            public Result<List<MetadataTableSensitivityDTO>> getSensitivity(Long datasourceId, String database,
                                                                            String schema, String tables) {
                return null;
            }

            @Override
            public Result<List<MetadataTableSensitivityDTO>> listTables(Long datasourceId, String database,
                                                                        String schema) {
                return null;
            }
        };
    }
}
