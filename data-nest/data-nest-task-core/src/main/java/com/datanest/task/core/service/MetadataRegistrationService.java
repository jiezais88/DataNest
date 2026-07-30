package com.datanest.task.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.MetadataSourceStatus;
import com.datanest.common.constant.SourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.entity.MetadataColumn;
import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.SyncJob;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.mapper.MetadataColumnMapper;
import com.datanest.task.core.mapper.MetadataTableMapper;
import com.datanest.task.core.mapper.SyncJobMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MetadataRegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataRegistrationService.class);
    private static final String SOURCE_TYPE = SourceType.BUILTIN_DORIS.getCode();
    private static final Long BUILTIN_DORIS_DATASOURCE_ID = -1L;

    @Value("${datanest.engineering.addax.target-database:datanest}")
    private String targetDatabase;

    private final DataSource dorisDataSource;

    private final SyncJobMapper syncJobMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final EncryptionConfig encryptionConfig;
    private final ConnectionTester connectionTester;

    public MetadataRegistrationService(SyncJobMapper syncJobMapper, DataSourceConnectionMapper dataSourceConnectionMapper,
                                       MetadataTableMapper metadataTableMapper, MetadataColumnMapper metadataColumnMapper,
                                       EncryptionConfig encryptionConfig, ConnectionTester connectionTester,
                                       @org.springframework.beans.factory.annotation.Qualifier("dorisDataSource") DataSource dorisDataSource) {
        this.syncJobMapper = syncJobMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.encryptionConfig = encryptionConfig;
        this.connectionTester = connectionTester;
        this.dorisDataSource = dorisDataSource;
    }

    @Transactional(rollbackFor = Exception.class)
    public void register(Long syncJobId) {
        SyncJob job = syncJobMapper.selectById(syncJobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.SYNC_JOB_NOT_FOUND);
        }

        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        String targetDb = resolveTargetDatabase(job);

        try (Connection connection = dorisDataSource.getConnection()) {
            for (String sourceTable : job.getSourceTables()) {
                String targetTableName = resolveTargetTableName(job, sourceTable);
                registerTable(targetDb, targetTableName, connection);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("注册 Doris 元数据失败: syncJobId={}", syncJobId, e);
            throw new BusinessException(ErrorCode.ADDAX_EXECUTION_FAILED, "注册 Doris 元数据失败: " + e.getMessage());
        }
    }

    private String resolveTargetDatabase(SyncJob job) {
        return StringUtils.hasText(job.getTargetDatabase()) ? job.getTargetDatabase() : targetDatabase;
    }

    private String resolveTargetTableName(SyncJob job, String sourceTable) {
        if (StringUtils.hasText(job.getTargetTable())) {
            return job.getTargetTable();
        }
        String sourceDb = StringUtils.hasText(job.getSourceDatabase()) ? job.getSourceDatabase()
                : (StringUtils.hasText(job.getSourceSchema()) ? job.getSourceSchema() : "default");
        String db = sourceDb.replaceAll("[^a-zA-Z0-9_]", "_");
        String table = sourceTable.replaceAll("[^a-zA-Z0-9_]", "_");
        return "sync_" + db + "_" + table;
    }

    private void registerTable(String targetDb, String targetTableName, Connection connection) throws SQLException {
        MetadataTable table = findOrCreateTable(targetDb, targetTableName);
        List<MetadataColumn> columns = extractColumns(connection, targetDb, targetTableName, table.getId());
        refreshColumns(table.getId(), columns);
        table.setColumnCount(columns.size());
        table.setUpdatedAt(LocalDateTime.now());
        metadataTableMapper.updateById(table);
        logger.info("刷新 BUILTIN_DORIS 元数据表字段: tableId={}, table={}, count={}",
                table.getId(), targetTableName, columns.size());
    }

    private MetadataTable findOrCreateTable(String targetDb, String targetTableName) {
        QueryWrapper<MetadataTable> wrapper = new QueryWrapper<>();
        wrapper.eq("datasource_id", BUILTIN_DORIS_DATASOURCE_ID)
                .eq("database_name", targetDb)
                .apply("COALESCE(schema_name, '') = {0}", "")
                .eq("table_name", targetTableName);
        MetadataTable existing = metadataTableMapper.selectOne(wrapper);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            MetadataTable table = new MetadataTable();
            table.setDatasourceId(BUILTIN_DORIS_DATASOURCE_ID);
            table.setDatabaseName(targetDb);
            table.setSchemaName(null);
            table.setTableName(targetTableName);
            table.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
            table.setSourceType(SOURCE_TYPE);
            table.setColumnCount(0);
            table.setCreatedAt(now);
            table.setUpdatedAt(now);
            metadataTableMapper.insert(table);
            logger.info("新增 BUILTIN_DORIS 元数据表: table={}", targetTableName);
            return table;
        }
        existing.setSourceStatus(MetadataSourceStatus.ONLINE.getCode());
        existing.setSourceType(SOURCE_TYPE);
        existing.setUpdatedAt(now);
        metadataTableMapper.updateById(existing);
        logger.info("更新 BUILTIN_DORIS 元数据表: tableId={}, table={}", existing.getId(), targetTableName);
        return existing;
    }

    private List<MetadataColumn> extractColumns(Connection connection, String targetDb, String targetTableName, Long tableId) throws SQLException {
        List<MetadataColumn> columns = new ArrayList<>();
        String sql = "SELECT column_name, data_type, is_nullable, column_default, ordinal_position " +
                "FROM information_schema.columns " +
                "WHERE table_schema = ? AND table_name = ? " +
                "ORDER BY ordinal_position";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetDb);
            ps.setString(2, targetTableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MetadataColumn column = new MetadataColumn();
                    column.setTableId(tableId);
                    column.setColumnName(rs.getString("column_name"));
                    column.setDataType(rs.getString("data_type"));
                    String nullable = rs.getString("is_nullable");
                    column.setNullable(!"NO".equalsIgnoreCase(nullable));
                    column.setColumnDefault(rs.getString("column_default"));
                    column.setOrdinalPosition(rs.getInt("ordinal_position"));
                    column.setSourceType(SOURCE_TYPE);
                    columns.add(column);
                }
            }
        }
        return columns;
    }

    private void refreshColumns(Long tableId, List<MetadataColumn> columns) {
        LocalDateTime now = LocalDateTime.now();

        Map<String, MetadataColumn> existingMap = new HashMap<>();
        List<MetadataColumn> existingColumns = metadataColumnMapper.selectList(
                new QueryWrapper<MetadataColumn>().eq("table_id", tableId));
        for (MetadataColumn existing : existingColumns) {
            existingMap.put(existing.getColumnName(), existing);
        }

        for (MetadataColumn column : columns) {
            MetadataColumn existing = existingMap.get(column.getColumnName());
            if (existing != null) {
                column.setId(existing.getId());
                column.setColumnComment(existing.getColumnComment());
                column.setManualComment(existing.getManualComment());
                column.setUpdatedAt(now);
                metadataColumnMapper.updateById(column);
            } else {
                column.setCreatedAt(now);
                column.setUpdatedAt(now);
                metadataColumnMapper.insert(column);
            }
        }
    }

    // ============================================
    // Phase 4（Sprint 3）：registerFromSql — 从 SQL 字符串提取表结构并注册
    // 用例：dag_node 为 SQL 节点时，回调里把用户写的 CREATE TABLE / CTAS 解析出来
    //  注册到 metadata_table + metadata_column
    // ============================================

    /**
     * 解析 SQL，提取其中的 CREATE TABLE / DROP TABLE 语句，
     * 把目标库的内置 Doris 表注册到元数据表（跟 register(syncJobId) 行为一致）
     *
     * @param sql        完整 SQL 字符串（支持多条 SQL 用 ; 分隔）
     * @param operatorId 操作人 ID
     * @return 注册的表名列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> registerFromSql(String sql, Long operatorId) {
        if (!StringUtils.hasText(sql)) {
            throw new BusinessException(ErrorCode.SQL_PARSE_FAILED, "SQL 不能为空");
        }

        // 1. 先在 Doris 里执行 SQL（让表真实存在或变更）
        String targetDb = targetDatabase;

        // 2. 解析每条 SQL，分类处理
        List<String> registeredTables = new ArrayList<>();
        String[] statements = splitSqlStatements(sql);
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            try {
                net.sf.jsqlparser.statement.Statement parsed =
                        net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(trimmed);

                if (parsed instanceof net.sf.jsqlparser.statement.create.table.CreateTable) {
                    // CREATE TABLE xxx (col1 type, col2 type, ...) 或 CREATE TABLE xxx AS SELECT (CTAS)
                    // Sprint 3 P2-3：CTAS 也被 JSqlParser 解析为 CreateTable，用 getSelect() != null 判断
                    net.sf.jsqlparser.statement.create.table.CreateTable ct =
                            (net.sf.jsqlparser.statement.create.table.CreateTable) parsed;
                    String tableName = extractTableName(ct.getTable());
                    if (tableName == null) {
                        logger.warn("无法提取 CREATE TABLE 表名: {}", trimmed);
                        continue;
                    }
                    if (ct.getSelect() != null) {
                        logger.info("检测到 CTAS: {} (基于 SELECT)", tableName);
                    }
                    executeAndRegister(targetDb, tableName, operatorId);
                    registeredTables.add(tableName);
                } else if (parsed instanceof net.sf.jsqlparser.statement.drop.Drop) {
                    // DROP TABLE xxx（暂时不主动删元数据表，避免误删；后续可加白名单）
                    logger.info("跳过 DROP TABLE 元数据同步: {}", trimmed);
                } else {
                    // 其他语句（INSERT/UPDATE/DELETE/SELECT）不参与元数据注册
                    logger.debug("非 DDL 语句，跳过元数据注册: {}", trimmed);
                }
            } catch (net.sf.jsqlparser.JSQLParserException e) {
                // 解析失败的非 DDL 语句直接忽略（不抛异常，让数据执行继续）
                logger.debug("SQL 解析失败（非 DDL 跳过）: {}", trimmed, e);
            }
        }
        return registeredTables;
    }

    /**
     * 注册单个表到元数据（含建表 / 刷新列）
     */
    private void executeAndRegister(String targetDb, String tableName, Long operatorId) {
        try (Connection conn = dorisDataSource.getConnection()) {
            // 先确保表真实存在（外部系统已执行过 SQL；这里做幂等保护）
            if (!tableExists(conn, targetDb, tableName)) {
                logger.warn("表 {}.{} 在 Doris 中不存在，元数据无法注册", targetDb, tableName);
                return;
            }
            MetadataTable table = findOrCreateTable(targetDb, tableName);
            table.setCreatedBy(operatorId);
            table.setUpdatedBy(operatorId);
            table.setUpdatedAt(LocalDateTime.now());
            List<MetadataColumn> columns = extractColumns(conn, targetDb, tableName, table.getId());
            refreshColumns(table.getId(), columns);
            table.setColumnCount(columns.size());
            metadataTableMapper.updateById(table);
            logger.info("从 SQL 注册元数据: db={}, table={}, cols={}", targetDb, tableName, columns.size());
        } catch (Exception e) {
            logger.error("从 SQL 注册元数据失败: db={}, table={}", targetDb, tableName, e);
            throw new BusinessException(ErrorCode.METADATA_REGISTRATION_FAILED,
                    "元数据注册失败: " + e.getMessage());
        }
    }

    private boolean tableExists(Connection conn, String db, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ? LIMIT 1")) {
            ps.setString(1, db);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 简单 SQL 切分（按 ; 分隔，忽略字符串内的 ;）
     */
    private String[] splitSqlStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
                current.append(c);
            } else if (c == ';' && !inString) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result.toArray(new String[0]);
    }

    private String extractTableName(net.sf.jsqlparser.schema.Table table) {
        if (table == null) return null;
        if (table.getSchemaName() != null) {
            return table.getSchemaName() + "." + table.getName();
        }
        return table.getName();
    }

}
