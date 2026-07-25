package com.datanest.governance.collect;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.governance.entity.DataSourceConnection;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Component
public class MysqlMetadataExtractor implements MetadataExtractor {

    private final EncryptionConfig encryptionConfig;

    public MysqlMetadataExtractor(EncryptionConfig encryptionConfig) {
        this.encryptionConfig = encryptionConfig;
    }

    @Override
    public List<String> extractSchemas(DataSourceConnection ds) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = openConnection(ds)) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    String db = rs.getString(1);
                    if (!isSystemDatabase(db)) {
                        schemas.add(db);
                    }
                }
            }
        }
        return schemas;
    }

    @Override
    public List<TableMetadata> extractTables(DataSourceConnection ds, String schema) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        String jdbcUrl = buildUrl(ds, schema);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, ds.getUsername(), encryptionConfig.decrypt(ds.getEncryptedPassword()))) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(schema, null, null, new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setDatabaseName(schema);
                    table.setSchemaName(schema);
                    table.setTableName(rs.getString("TABLE_NAME"));
                    table.setTableComment(rs.getString("REMARKS"));
                    table.setColumns(extractColumns(metaData, schema, table.getTableName()));
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private List<ColumnMetadata> extractColumns(DatabaseMetaData metaData, String schema, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(schema, null, tableName, null)) {
            while (rs.next()) {
                ColumnMetadata col = new ColumnMetadata();
                col.setColumnName(rs.getString("COLUMN_NAME"));
                col.setDataType(rs.getString("TYPE_NAME"));
                col.setColumnComment(rs.getString("REMARKS"));
                col.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                col.setColumnDefault(rs.getString("COLUMN_DEF"));
                col.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                columns.add(col);
            }
        }
        return columns;
    }

    private Connection openConnection(DataSourceConnection ds) throws SQLException {
        return DriverManager.getConnection(buildUrl(ds, ds.getDatabaseName()), ds.getUsername(), encryptionConfig.decrypt(ds.getEncryptedPassword()));
    }

    private String buildUrl(DataSourceConnection ds, String database) {
        return "jdbc:mysql://" + ds.getHost() + ":" + ds.getPort() + "/" + database
                + "?useSSL=false&useUnicode=true&characterEncoding=UTF-8&useInformationSchema=true&serverTimezone=Asia/Shanghai";
    }

    private boolean isSystemDatabase(String db) {
        return "information_schema".equalsIgnoreCase(db)
                || "mysql".equalsIgnoreCase(db)
                || "performance_schema".equalsIgnoreCase(db)
                || "sys".equalsIgnoreCase(db);
    }
}
