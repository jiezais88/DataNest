package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.governance.dto.MetadataDatasourceDTO;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.MetadataColumn;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.MetadataColumnMapper;
import com.datanest.task.core.mapper.MetadataTableMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MetadataService {

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;

    private final String builtInDorisHost;
    private final int builtInDorisQueryPort;
    private final String builtInDorisUser;
    private final String builtInDorisPassword;

    public MetadataService(MetadataTableMapper metadataTableMapper, MetadataColumnMapper metadataColumnMapper,
                           DataSourceConnectionMapper dataSourceConnectionMapper,
                           @Value("${datanest.doris.fe-host:localhost}") String builtInDorisHost,
                           @Value("${datanest.doris.fe-query-port:9030}") int builtInDorisQueryPort,
                           @Value("${datanest.doris.user:root}") String builtInDorisUser,
                           @Value("${datanest.doris.password:}") String builtInDorisPassword) {
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.builtInDorisHost = builtInDorisHost;
        this.builtInDorisQueryPort = builtInDorisQueryPort;
        this.builtInDorisUser = builtInDorisUser;
        this.builtInDorisPassword = builtInDorisPassword;
    }

    @Transactional(readOnly = true)
    public List<MetadataDatasourceDTO> listDatasourceIds() {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("source_status", "ONLINE")
                .select("DISTINCT datasource_id, source_type")
                .orderByAsc("datasource_id");
        List<MetadataTable> rows = metadataTableMapper.selectList(wrapper);

        List<Long> ids = rows.stream()
                .map(MetadataTable::getDatasourceId)
                .distinct()
                .toList();

        if (ids.isEmpty()) {
            return List.of();
        }

        List<DataSourceConnection> connections = dataSourceConnectionMapper.selectList(
                Wrappers.<DataSourceConnection>query().in("id", ids));

        Map<Long, String> sourceTypeMap = rows.stream()
                .collect(Collectors.toMap(
                        MetadataTable::getDatasourceId,
                        MetadataTable::getSourceType,
                        (existing, replacement) -> sourceTypePriority(existing) >= sourceTypePriority(replacement)
                                ? existing
                                : replacement
                ));

        return ids.stream().map(id -> {
            MetadataDatasourceDTO dto = new MetadataDatasourceDTO();
            dto.setId(id);
            DataSourceConnection conn = connections.stream()
                    .filter(c -> id.equals(c.getId()))
                    .findFirst()
                    .orElse(null);
            String sourceType = sourceTypeMap.getOrDefault(id, "EXTERNAL");
            if (conn != null) {
                dto.setName(conn.getName());
                dto.setType(conn.getType());
                dto.setExists(true);
            } else if ("BUILTIN_DORIS".equals(sourceType)) {
                dto.setName("Doris（内置）");
                dto.setType("DORIS");
                dto.setExists(true);
            } else {
                dto.setName(null);
                dto.setType(null);
                dto.setExists(false);
            }
            dto.setSourceType(sourceType);
            return dto;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<String> listDatabases(Long datasourceId) {
        if (datasourceId == null) {
            return List.of();
        }
        return metadataTableMapper.selectDatabasesByDatasourceId(datasourceId);
    }

    @Transactional(readOnly = true)
    public List<String> listBuiltinDorisDatabases() {
        return JdbcSchemaExtractor.extractDatabases(
                        "DORIS", builtInDorisHost, builtInDorisQueryPort,
                        "information_schema", null,
                        builtInDorisUser, builtInDorisPassword)
                .stream()
                .filter(db -> !"__internal_schema".equals(db))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listSchemas(Long datasourceId, String databaseName) {
        return metadataTableMapper.selectSchemasByDatasourceIdAndDatabase(datasourceId, databaseName);
    }

    @Transactional(readOnly = true)
    public List<MetadataTable> listTables(Long datasourceId, String databaseName, String schemaName) {
        return metadataTableMapper.selectTablesByDatasourceDatabaseSchema(datasourceId, databaseName, schemaName);
    }

    @Transactional(readOnly = true)
    public List<String> listBuiltinDorisTables(String databaseName) {
        return JdbcSchemaExtractor.extractTables(
                "DORIS", builtInDorisHost, builtInDorisQueryPort,
                databaseName, null,
                builtInDorisUser, builtInDorisPassword);
    }

    @Transactional(readOnly = true)
    public MetadataTable getTable(Long tableId) {
        MetadataTable table = metadataTableMapper.selectTableDetailById(tableId);
        if (table == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        return table;
    }

    /**
     * 解析表记录，支持内置 Doris 伪 ID。
     */
    @Transactional(readOnly = true)
    public MetadataTable resolveTable(Long tableId) {
        return getTable(tableId);
    }

    @Transactional(readOnly = true)
    public List<MetadataColumn> listColumns(Long tableId) {
        return metadataColumnMapper.selectByTableId(tableId);
    }

    @Transactional
    public void updateTableComment(Long tableId, String manualComment) {
        MetadataTable table = getTable(tableId);
        table.setManualComment(manualComment);
        table.setUpdatedBy(currentUserId());
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
    }

    @Transactional
    public void updateColumnComment(Long columnId, String manualComment) {
        MetadataColumn column = metadataColumnMapper.selectById(columnId);
        if (column == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        column.setManualComment(manualComment);
        column.setUpdatedBy(currentUserId());
        column.setUpdatedAt(LocalDateTime.now());
        metadataColumnMapper.updateById(column);
    }

    @Transactional
    public void updateColumnRemark(Long columnId, String remark) {
        MetadataColumn column = metadataColumnMapper.selectById(columnId);
        if (column == null) {
            throw new BusinessException(ErrorCode.METADATA_NOT_FOUND);
        }
        column.setRemark(remark);
        column.setUpdatedBy(currentUserId());
        column.setUpdatedAt(LocalDateTime.now());
        metadataColumnMapper.updateById(column);
    }

    private Long currentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private int sourceTypePriority(String sourceType) {
        return "BUILTIN_DORIS".equals(sourceType) ? 1 : 0;
    }
}
