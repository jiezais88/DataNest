package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.constant.SourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.governance.dto.MetadataDatasourceDTO;
import com.datanest.governance.dto.MetadataTreeNodeDTO;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.entity.MetadataColumn;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.MetadataColumnMapper;
import com.datanest.task.core.mapper.MetadataTableMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
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
        wrapper.eq("source_status", MetadataSourceStatus.ONLINE.getCode())
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
            String sourceType = sourceTypeMap.getOrDefault(id, SourceType.EXTERNAL.getCode());
            if (conn != null) {
                dto.setName(conn.getName());
                dto.setType(conn.getType());
                dto.setExists(true);
            } else if (SourceType.BUILTIN_DORIS.getCode().equals(sourceType)) {
                dto.setName("Doris（内置）");
                dto.setType(DataSourceType.DORIS.getCode());
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
                        DataSourceType.DORIS.getCode(), builtInDorisHost, builtInDorisQueryPort,
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
                DataSourceType.DORIS.getCode(), builtInDorisHost, builtInDorisQueryPort,
                databaseName, null,
                builtInDorisUser, builtInDorisPassword);
    }

    /**
     * 按数据库名 / 模式名 / 表名模糊搜索，返回可完整渲染的树结构。
     */
    @Transactional(readOnly = true)
    public List<MetadataTreeNodeDTO> searchTree(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }

        List<MetadataTable> rows = metadataTableMapper.searchTablesByKeyword(keyword.trim());
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> datasourceIds = rows.stream()
                .map(MetadataTable::getDatasourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, DataSourceConnection> connectionMap = dataSourceConnectionMapper.selectList(
                        Wrappers.<DataSourceConnection>query().in("id", datasourceIds))
                .stream()
                .collect(Collectors.toMap(DataSourceConnection::getId, c -> c, (a, b) -> a));

        // datasource -> database -> schema -> table
        Map<Long, MetadataTreeNodeDTO> datasourceMap = new LinkedHashMap<>();
        Map<String, MetadataTreeNodeDTO> databaseMap = new LinkedHashMap<>();
        Map<String, MetadataTreeNodeDTO> schemaMap = new LinkedHashMap<>();

        for (MetadataTable row : rows) {
            Long datasourceId = row.getDatasourceId();
            String databaseName = row.getDatabaseName();
            String schemaName = row.getSchemaName();
            String sourceType = row.getSourceType();

            MetadataTreeNodeDTO datasourceNode = datasourceMap.computeIfAbsent(datasourceId, id -> {
                MetadataTreeNodeDTO node = new MetadataTreeNodeDTO();
                node.setId("ds-" + id);
                node.setType("datasource");
                boolean builtinDoris = SourceType.BUILTIN_DORIS.getCode().equals(sourceType);
                DataSourceConnection conn = connectionMap.get(id);
                if (conn != null) {
                    node.setName(conn.getName() + (conn.getType() != null ? " (" + conn.getType() + ")" : ""));
                    node.setDatasourceType(conn.getType());
                    node.setExists(true);
                } else if (builtinDoris) {
                    node.setName("Doris（内置）");
                    node.setDatasourceType(DataSourceType.DORIS.getCode());
                    node.setExists(true);
                } else {
                    node.setName("数据源 " + String.valueOf(id).substring(Math.max(0, String.valueOf(id).length() - 6)) + "（已删除）");
                    node.setExists(false);
                }
                node.setDatasourceId(id);
                node.setSourceType(sourceType);
                node.setChildren(new ArrayList<>());
                return node;
            });

            String dbKey = datasourceId + "#" + databaseName;
            MetadataTreeNodeDTO databaseNode = databaseMap.computeIfAbsent(dbKey, k -> {
                MetadataTreeNodeDTO node = new MetadataTreeNodeDTO();
                node.setId("db-" + datasourceId + "-" + databaseName);
                node.setType("database");
                node.setName(databaseName);
                node.setDatabaseName(databaseName);
                node.setDatasourceId(datasourceId);
                node.setDatasourceType(datasourceNode.getDatasourceType());
                node.setChildren(new ArrayList<>());
                datasourceNode.getChildren().add(node);
                return node;
            });

            boolean noSchema = schemaName == null || schemaName.isBlank();
            if (noSchema) {
                // MySQL / Doris：database 下直接挂 table
                MetadataTreeNodeDTO tableNode = buildTableNode(row);
                databaseNode.getChildren().add(tableNode);
            } else {
                String schemaKey = dbKey + "#" + schemaName;
                MetadataTreeNodeDTO schemaNode = schemaMap.computeIfAbsent(schemaKey, k -> {
                    MetadataTreeNodeDTO node = new MetadataTreeNodeDTO();
                    node.setId("schema-" + datasourceId + "-" + databaseName + "-" + schemaName);
                    node.setType("schema");
                    node.setName(schemaName);
                    node.setDatabaseName(databaseName);
                    node.setSchemaName(schemaName);
                    node.setDatasourceId(datasourceId);
                    node.setDatasourceType(datasourceNode.getDatasourceType());
                    node.setChildren(new ArrayList<>());
                    databaseNode.getChildren().add(node);
                    return node;
                });
                MetadataTreeNodeDTO tableNode = buildTableNode(row);
                schemaNode.getChildren().add(tableNode);
            }
        }

        // 统计 database / schema 下的子表数量
        for (MetadataTreeNodeDTO datasource : datasourceMap.values()) {
            for (MetadataTreeNodeDTO database : datasource.getChildren()) {
                if (database.getChildren() != null && !database.getChildren().isEmpty()
                        && "table".equals(database.getChildren().get(0).getType())) {
                    database.setCount(database.getChildren().size());
                }
                for (MetadataTreeNodeDTO child : database.getChildren()) {
                    if ("schema".equals(child.getType()) && child.getChildren() != null) {
                        child.setCount(child.getChildren().size());
                    }
                }
            }
        }

        return new ArrayList<>(datasourceMap.values());
    }

    private MetadataTreeNodeDTO buildTableNode(MetadataTable row) {
        MetadataTreeNodeDTO node = new MetadataTreeNodeDTO();
        node.setId("table-" + row.getId());
        node.setType("table");
        node.setName(row.getTableName());
        node.setDatabaseName(row.getDatabaseName());
        node.setSchemaName(row.getSchemaName());
        node.setDatasourceId(row.getDatasourceId());
        node.setCount(row.getColumnCount() != null ? row.getColumnCount() : 0);
        return node;
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
        return SourceType.BUILTIN_DORIS.getCode().equals(sourceType) ? 1 : 0;
    }
}
