package com.datanest.governance.api;

import com.datanest.common.model.Result;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import com.datanest.governance.api.fallback.GovernanceMetadataApiFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 治理服务元数据查询内部 Feign 契约（Sprint 10 F1/F2 数据服务闸门）。
 * <p>
 * 供数据服务在 SQL 执行/API 创建前批量查询表敏感度（fail-closed 语义：
 * 不可达时返回 null，调用方拒绝执行并提示「分级服务暂不可用」，见 Blocker 3 已确认）。
 */
@FeignClient(name = "data-nest-governance", path = "/governance/internal", contextId = "governanceMetadataApi",
        fallbackFactory = GovernanceMetadataApiFallbackFactory.class)
public interface GovernanceMetadataApi {

    /**
     * 批量查表敏感度（SQL 终端/API 闸门用）。
     *
     * @param datasourceId 数据源 ID（内置 Doris 恒为 -1）
     * @param database     库名（可空，空时按 datasourceId 全局匹配）
     * @param schema       schema 名（可空，MySQL/Doris 无 schema 传空）
     * @param tables       表名列表（逗号分隔，单次 ≤ 100）
     * @return 表敏感度列表；governance 不可达时返回 null（调用方 fail-closed）
     */
    @GetMapping("/metadata/tables/sensitivity")
    Result<List<MetadataTableSensitivityDTO>> getSensitivity(@RequestParam("datasourceId") Long datasourceId,
                                                             @RequestParam(value = "database", required = false) String database,
                                                             @RequestParam(value = "schema", required = false) String schema,
                                                             @RequestParam("tables") String tables);

    /**
     * 数据源下表清单（含敏感度，SQL 终端表选择器用；数据服务侧过滤机密）。
     *
     * @param datasourceId 数据源 ID（内置 Doris 恒为 -1）
     * @param database     库名（可空，空时按 datasourceId 全局匹配）
     * @param schema       schema 名（可空）
     * @return 表清单；governance 不可达时返回 null
     */
    @GetMapping("/metadata/tables")
    Result<List<MetadataTableSensitivityDTO>> listTables(@RequestParam("datasourceId") Long datasourceId,
                                                         @RequestParam(value = "database", required = false) String database,
                                                         @RequestParam(value = "schema", required = false) String schema);
}
