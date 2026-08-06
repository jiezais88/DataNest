package com.datanest.task.core.collect;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.task.core.entity.DataSourceConnection;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Component
public class SqlServerMetadataExtractor implements MetadataExtractor {

    private static final String SCHEMA_SQL =
            "SELECT name FROM sys.schemas " +
                    "WHERE name NOT IN ('sys', 'information_schema', 'guest') " +
                    "ORDER BY name";

    private static final String TABLE_SQL =
            "SELECT t.table_name, p.value AS table_comment " +
                    "FROM information_schema.tables t " +
                    "LEFT JOIN sys.extended_properties p ON p.major_id = OBJECT_ID(t.table_schema + '.' + t.table_name) " +
                    "    AND p.minor_id = 0 AND p.class = 1 AND p.name = 'MS_Description' " +
                    "WHERE t.table_schema = ? AND t.table_type = 'BASE TABLE' " +
                    "ORDER BY t.table_name";

    private static final String COLUMN_SQL =
            "SELECT c.column_name, c.data_type, c.character_maximum_length, c.numeric_precision, c.numeric_scale, " +
                    "       c.is_nullable, c.column_default, c.ordinal_position, " +
                    "       p.value AS column_comment " +
                    "FROM information_schema.columns c " +
                    "LEFT JOIN sys.extended_properties p ON p.major_id = OBJECT_ID(c.table_schema + '.' + c.table_name) " +
                    "    AND p.minor_id = c.ordinal_position AND p.class = 1 AND p.name = 'MS_Description' " +
                    "WHERE c.table_schema = ? AND c.table_name = ? " +
                    "ORDER BY c.ordinal_position";

    private final EncryptionConfig encryptionConfig;

    public SqlServerMetadataExtractor(EncryptionConfig encryptionConfig) {
        this.encryptionConfig = encryptionConfig;
    }

    @Override
    public List<String> extractSchemas(DataSourceConnection ds) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = openConnection(ds);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SCHEMA_SQL)) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    @Override
    public List<TableMetadata> extractTables(DataSourceConnection ds, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            schema = "dbo";
        }
        List<TableMetadata> tables = new ArrayList<>();
        try (Connection conn = openConnection(ds);
             PreparedStatement ps = conn.prepareStatement(TABLE_SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setDatabaseName(ds.getDatabaseName());
                    table.setSchemaName(schema);
                    table.setTableName(rs.getString("table_name"));
                    table.setTableComment(rs.getString("table_comment"));
                    table.setColumns(extractColumns(conn, schema, table.getTableName()));
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private List<ColumnMetadata> extractColumns(Connection conn, String schema, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(COLUMN_SQL)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMetadata col = new ColumnMetadata();
                    col.setColumnName(rs.getString("column_name"));
                    col.setDataType(buildDataType(rs));
                    col.setColumnComment(rs.getString("column_comment"));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    col.setColumnDefault(rs.getString("column_default"));
                    col.setOrdinalPosition(rs.getInt("ordinal_position"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String type = rs.getString("data_type");
        int charLength = rs.getInt("character_maximum_length");
        if (!rs.wasNull() && charLength > 0) {
            return String.format("%s(%d)", type, charLength);
        }
        int precision = rs.getInt("numeric_precision");
        int scale = rs.getInt("numeric_scale");
        if (!rs.wasNull() && precision > 0) {
            return scale > 0 ? String.format("%s(%d,%d)", type, precision, scale) : String.format("%s(%d)", type, precision);
        }
        return type;
    }

    private Connection openConnection(DataSourceConnection ds) throws SQLException {
        String url = "jdbc:sqlserver://" + ds.getHost() + ":" + ds.getPort()
                + ";databaseName=" + ds.getDatabaseName()
                + ";encrypt=false;trustServerCertificate=true;loginTimeout=10";
        return DriverManager.getConnection(url, ds.getUsername(), encryptionConfig.decrypt(ds.getEncryptedPassword()));
    }
}
