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

import java.sql.*;
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

    @Value("${datanest.doris.fe-host:localhost}")
    private String dorisFeHost;

    @Value("${datanest.doris.fe-query-port:9030}")
    private int dorisFePort;

    @Value("${datanest.doris.user:root}")
    private String dorisUser;

    @Value("${datanest.doris.password:}")
    private String dorisPassword;

    private final SyncJobMapper syncJobMapper;
    private final DataSourceConnectionMapper dataSourceConnectionMapper;
    private final MetadataTableMapper metadataTableMapper;
    private final MetadataColumnMapper metadataColumnMapper;
    private final EncryptionConfig encryptionConfig;
    private final ConnectionTester connectionTester;

    public MetadataRegistrationService(SyncJobMapper syncJobMapper, DataSourceConnectionMapper dataSourceConnectionMapper,
                                       MetadataTableMapper metadataTableMapper, MetadataColumnMapper metadataColumnMapper,
                                       EncryptionConfig encryptionConfig, ConnectionTester connectionTester) {
        this.syncJobMapper = syncJobMapper;
        this.dataSourceConnectionMapper = dataSourceConnectionMapper;
        this.metadataTableMapper = metadataTableMapper;
        this.metadataColumnMapper = metadataColumnMapper;
        this.encryptionConfig = encryptionConfig;
        this.connectionTester = connectionTester;
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
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                dorisFeHost, dorisFePort, targetDb);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, dorisUser, dorisPassword)) {
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

}
