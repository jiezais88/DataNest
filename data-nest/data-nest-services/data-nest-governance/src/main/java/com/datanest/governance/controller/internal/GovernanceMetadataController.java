package com.datanest.governance.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.model.Result;
import com.datanest.governance.api.GovernanceMetadataApi;
import com.datanest.governance.api.dto.MetadataTableSensitivityDTO;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.MetadataTableMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 治理域元数据查询内部接口（实现 governance-api 的 GovernanceMetadataApi 契约）。
 * <p>
 * 仅供服务间内部调用（Sprint 10 F1/F2：数据服务 SQL 终端/API 创建的敏感度闸门），
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 * <p>
 * fail-closed：本端点不可达时 Feign fallback 返回 null，数据服务拒绝执行并提示「分级服务暂不可用」。
 */
@Hidden // 内部 Feign 契约端点，不进接口文档
@RestController
@RequestMapping("/internal")
public class GovernanceMetadataController {

    private final MetadataTableMapper metadataTableMapper;

    public GovernanceMetadataController(MetadataTableMapper metadataTableMapper) {
        this.metadataTableMapper = metadataTableMapper;
    }

    /**
     * 批量查表敏感度（SQL 终端/API 闸门用）。
     * 命中条件：datasource_id + database_name（可空）+ COALESCE(schema_name,'') 匹配 + table_name IN。
     * 返回该表最新敏感度（sensitivity_level/api_exempted/source_status）；未打标行默认 PUBLIC（DB 默认值）。
     */
    @GetMapping("/metadata/tables/sensitivity")
    public Result<List<MetadataTableSensitivityDTO>> getSensitivity(@RequestParam("datasourceId") Long datasourceId,
                                                                    @RequestParam(value = "database", required = false) String database,
                                                                    @RequestParam(value = "schema", required = false) String schema,
                                                                    @RequestParam("tables") String tables) {
        List<String> tableList = Arrays.stream(tables.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (tableList.isEmpty()) {
            return Result.ok(List.of());
        }
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", datasourceId);
        if (StringUtils.hasText(database)) {
            wrapper.eq("database_name", database);
        }
        // schema 可空时不限定 schema：跨 schema 同名表也能命中（闸门保守，任一命中机密即拦截）
        if (StringUtils.hasText(schema)) {
            wrapper.eq("schema_name", schema);
        }
        wrapper.in("table_name", tableList);
        List<MetadataTable> rows = metadataTableMapper.selectList(wrapper);
        return Result.ok(rows.stream().map(this::toSensitivityDTO).collect(Collectors.toList()));
    }

    /**
     * 数据源下表清单（含敏感度，SQL 终端表选择器用；数据服务侧过滤机密）。
     * 仅返回 ONLINE 元数据，按库名+表名排序。
     */
    @GetMapping("/metadata/tables")
    public Result<List<MetadataTableSensitivityDTO>> listTables(@RequestParam("datasourceId") Long datasourceId,
                                                                @RequestParam(value = "database", required = false) String database,
                                                                @RequestParam(value = "schema", required = false) String schema) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", datasourceId);
        wrapper.eq("source_status", MetadataSourceStatus.ONLINE.getCode());
        if (StringUtils.hasText(database)) {
            wrapper.eq("database_name", database);
        }
        if (StringUtils.hasText(schema)) {
            wrapper.eq("schema_name", schema);
        }
        wrapper.orderByAsc("database_name", "table_name");
        List<MetadataTable> rows = metadataTableMapper.selectList(wrapper);
        return Result.ok(rows.stream().map(this::toSensitivityDTO).collect(Collectors.toList()));
    }

    private MetadataTableSensitivityDTO toSensitivityDTO(MetadataTable t) {
        MetadataTableSensitivityDTO dto = new MetadataTableSensitivityDTO();
        dto.setDatasourceId(t.getDatasourceId());
        dto.setDatabaseName(t.getDatabaseName());
        dto.setSchemaName(t.getSchemaName());
        dto.setTableName(t.getTableName());
        dto.setSensitivityLevel(t.getSensitivityLevel());
        dto.setApiExempted(t.getApiExempted());
        dto.setSourceStatus(t.getSourceStatus());
        return dto;
    }
}
