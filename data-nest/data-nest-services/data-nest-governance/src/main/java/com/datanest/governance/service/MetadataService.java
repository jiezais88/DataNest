package com.datanest.governance.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.constant.SourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.engineering.api.EngineeringDatasourceApi;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.IdsRequest;
import com.datanest.governance.dto.MetadataDatasourceDTO;
import com.datanest.governance.dto.MetadataTreeNodeDTO;
import com.datanest.governance.entity.MetadataColumn;
import com.datanest.governance.entity.MetadataTable;
import com.datanest.governance.mapper.MetadataColumnMapper;
import com.datanest.governance.mapper.MetadataTableMapper;
import com.datanest.common.internal.RemoteCalls;
import com.datanest.common.model.Result;
import com.datanest.task.core.support.SystemUserResolver;
import com.datanest.system.api.SystemUserApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MetadataService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataService.class);

    /** 搜索关键词最大长度，超长截断以保护 LIKE 查询 */
    private static final int MAX_SEARCH_KEYWORD_LENGTH = 100;

    /** 搜索结果最大返回条数，防止关键词过泛时全表返回（SQL 在 task-core，无法在此加 LIMIT） */
    private static final int MAX_SEARCH_RESULTS = 100;

    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final EngineeringDatasourceApi datasourceApi;
    private final SystemUserApi systemUserApi;

    private final String builtInDorisHost;
    private final int builtInDorisQueryPort;
    private final String builtInDorisUser;
    private final String builtInDorisPassword;

    public MetadataService(MetadataTableMapper metadataTableMapper, MetadataColumnMapper metadataColumnMapper,
                           EngineeringDatasourceApi datasourceApi,
                           SystemUserApi systemUserApi,
                           @Value("${datanest.doris.fe-host:localhost}") String builtInDorisHost,
                           @Value("${datanest.doris.fe-query-port:9030}") int builtInDorisQueryPort,
                           @Value("${datanest.doris.user:root}") String builtInDorisUser,
                           @Value("${datanest.doris.password:}") String builtInDorisPassword) {
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.datasourceApi = datasourceApi;
        this.systemUserApi = systemUserApi;
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

        Map<Long, DataSourceInfo> connectionMap = batchGetDatasources(ids);

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
            DataSourceInfo conn = connectionMap.get(id);
            String sourceType = sourceTypeMap.getOrDefault(id, SourceType.EXTERNAL.getCode());
            if (conn != null) {
                dto.setName(conn.getName());
                dto.setType(conn.getType());
                dto.setExists(true);
            } else if (SourceType.BUILTIN_DORIS.getCode().equals(sourceType)) {
                dto.setName("Doris 数仓");
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
        List<MetadataTable> tables = metadataTableMapper.selectTablesByDatasourceDatabaseSchema(datasourceId, databaseName, schemaName);
        applyDatasourceNames(tables);
        applyUsernameNames(tables);
        return tables;
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
        String trimmed = keyword == null ? "" : keyword.trim();
        // 空白关键词直接返回空结果，避免三列 LIKE '%%' 全库扫描
        if (trimmed.isEmpty()) {
            return List.of();
        }
        // 纯通配符关键词（如 %、%%_）等价于全表匹配，直接拒绝
        if (trimmed.replace("%", "").replace("_", "").isBlank()) {
            return List.of();
        }
        // 超长关键词截断，保护 LIKE 查询
        if (trimmed.length() > MAX_SEARCH_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_SEARCH_KEYWORD_LENGTH);
        }

        List<MetadataTable> rows = metadataTableMapper.searchTablesByKeyword(trimmed);
        if (rows.isEmpty()) {
            return List.of();
        }
        // SQL 写死在 task-core 的 mapper 中，此处无法在 SQL 层加 LIMIT，
        // 先在服务层截断保护返回规模；彻底修复需在 searchTablesByKeyword 的 SQL 上加 LIMIT
        if (rows.size() > MAX_SEARCH_RESULTS) {
            logger.warn("Metadata search hit result cap: keyword={}, total={}, capped={}",
                    trimmed, rows.size(), MAX_SEARCH_RESULTS);
            rows = rows.subList(0, MAX_SEARCH_RESULTS);
        }

        List<Long> datasourceIds = rows.stream()
                .map(MetadataTable::getDatasourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, DataSourceInfo> connectionMap = batchGetDatasources(datasourceIds);

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
                DataSourceInfo conn = connectionMap.get(id);
                if (conn != null) {
                    node.setName(conn.getName() + (conn.getType() != null ? " (" + conn.getType() + ")" : ""));
                    node.setDatasourceType(conn.getType());
                    node.setExists(true);
                } else if (builtinDoris) {
                    node.setName("Doris 数仓");
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
        applyDatasourceNames(List.of(table));
        applyUsernameNames(List.of(table));
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

    /**
     * 批量回填数据源名称/类型（微服务化 5.0：替代原 datasource_connection 跨域 JOIN）。
     * 全部 datasourceId 一次 Feign 批量调用；engineering 不可用时经 RemoteCalls 降级为空 Map
     * （datasourceName/datasourceType 列为 null），不阻断查询。
     * 内置 Doris（datasource_id = -1）在 engineering 查不到，与原 LEFT JOIN 未命中一致，保持 null。
     */
    private void applyDatasourceNames(List<MetadataTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        List<Long> dsIds = tables.stream()
                .map(MetadataTable::getDatasourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, DataSourceInfo> dsMap = batchGetDatasources(dsIds);
        for (MetadataTable table : tables) {
            DataSourceInfo ds = dsMap.get(table.getDatasourceId());
            if (ds != null) {
                table.setDatasourceName(ds.getName());
                table.setDatasourceType(ds.getType());
            }
        }
    }

    private void applyUsernameNames(List<MetadataTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        List<Long> userIds = tables.stream()
                .flatMap(t -> Stream.of(t.getCreatedBy(), t.getUpdatedBy(), t.getOwnerUserId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> usernameMap = usernames(userIds);
        for (MetadataTable table : tables) {
            // 注意 usernames() 返回 immutable map，get(null) 会 NPE；created_by/updated_by/owner_user_id
            // 对自动采集的表均可为 null，三处都必须判空（2026-08-07 联调踩：metadata_table 全 null 行 500）
            table.setCreatedByName(table.getCreatedBy() == null ? null : usernameMap.get(table.getCreatedBy()));
            table.setUpdatedByName(table.getUpdatedBy() == null ? null : usernameMap.get(table.getUpdatedBy()));
            // Sprint 7 F1：表负责人名回填（资产详情页头部/基础信息展示）
            table.setOwnerName(table.getOwnerUserId() == null ? null : usernameMap.get(table.getOwnerUserId()));
        }
    }

    private int sourceTypePriority(String sourceType) {
        return SourceType.BUILTIN_DORIS.getCode().equals(sourceType) ? 1 : 0;
    }

    /**
     * 经 engineering 服务 Feign 批量查询数据源（id → 连接信息）。
     * 只读回填路径：engineering 不可用时降级为空 Map（数据源名/类型列退化），不阻断元数据查询。
     */
    private Map<Long, DataSourceInfo> batchGetDatasources(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return RemoteCalls.execute("engineering.datasource.batchGet", () -> {
            IdsRequest request = new IdsRequest();
            request.setIds(ids.stream().toList());
            Result<Map<Long, DataSourceInfo>> result = datasourceApi.batchGet(request);
            return result == null || result.data() == null ? Map.<Long, DataSourceInfo>of() : result.data();
        }, Map.of());
    }

    /**
     * 经 system 服务 Feign 批量查询 userId → username 映射。
     * system 不可用时降级为空 Map 并记 warn（列表页名称列退化为空），不拖垮本接口。
     * 委托 task-core SystemUserResolver。
     */
    private Map<Long, String> usernames(Collection<Long> userIds) {
        return SystemUserResolver.usernames(systemUserApi, userIds);
    }
}
