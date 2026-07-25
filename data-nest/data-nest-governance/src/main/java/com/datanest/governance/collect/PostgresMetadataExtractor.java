package com.datanest.governance.collect;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.governance.entity.DataSourceConnection;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Component
public class PostgresMetadataExtractor implements MetadataExtractor {

    private static final String TABLE_SQL =
            "SELECT t.table_schema, t.table_name, obj_description(c.oid, 'pg_class') AS table_comment " +
                    "FROM information_schema.tables t " +
                    "JOIN pg_class c ON c.relname = t.table_name " +
                    "JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema " +
                    "WHERE t.table_schema = ? AND t.table_type IN ('BASE TABLE', 'VIEW') " +
                    "ORDER BY t.table_name";

    private static final String COLUMN_SQL =
            "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, c.ordinal_position, " +
                    "       col_description(pgc.oid, a.attnum) AS column_comment " +
                    "FROM information_schema.columns c " +
                    "JOIN pg_class pgc ON pgc.relname = c.table_name " +
                    "JOIN pg_namespace pgn ON pgn.oid = pgc.relnamespace AND pgn.nspname = c.table_schema " +
                    "JOIN pg_attribute a ON a.attrelid = pgc.oid AND a.attname = c.column_name " +
                    "WHERE c.table_schema = ? AND c.table_name = ? " +
                    "ORDER BY c.ordinal_position, c.column_name";

    private final EncryptionConfig encryptionConfig;

    public PostgresMetadataExtractor(EncryptionConfig encryptionConfig) {
        this.encryptionConfig = encryptionConfig;
    }

    @Override
    public List<String> extractSchemas(DataSourceConnection ds) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = openConnection(ds);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT schema_name FROM information_schema.schemata " +
                             "WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast') " +
                             "AND schema_name NOT LIKE 'pg_%' ORDER BY schema_name")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }
        return schemas;
    }

    @Override
    public List<TableMetadata> extractTables(DataSourceConnection ds, String schema) throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        try (Connection conn = openConnection(ds);
             PreparedStatement ps = conn.prepareStatement(TABLE_SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setDatabaseName(ds.getDatabaseName());
                    table.setSchemaName(rs.getString("table_schema"));
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
                    col.setDataType(rs.getString("data_type"));
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

    private Connection openConnection(DataSourceConnection ds) throws SQLException {
        String url = "jdbc:postgresql://" + ds.getHost() + ":" + ds.getPort() + "/" + ds.getDatabaseName()
                + "?useSSL=false&applicationName=data-nest-governance";
        return DriverManager.getConnection(url, ds.getUsername(), encryptionConfig.decrypt(ds.getEncryptedPassword()));
    }
}
