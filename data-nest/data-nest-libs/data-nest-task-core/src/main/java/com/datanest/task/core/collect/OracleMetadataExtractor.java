package com.datanest.task.core.collect;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.engineering.api.dto.DataSourceInfo;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OracleMetadataExtractor implements MetadataExtractor {

    private static final String SCHEMA_SQL =
            "SELECT username FROM all_users " +
                    "WHERE username NOT IN ('SYS','SYSTEM','OUTLN','DBSNMP','APPQOSSYS','CTXSYS','MDSYS','OLAPSYS','ORDDATA','ORDPLUGINS','ORDSYS','SI_INFORMTN_SCHEMA','XDB','XS$NULL','WMSYS','ANONYMOUS','DIP','ORACLE_OCM') " +
                    "ORDER BY username";

    private static final String TABLE_SQL =
            "SELECT table_name FROM all_tables " +
                    "WHERE owner = ? " +
                    "ORDER BY table_name";

    private static final String TABLE_COMMENT_SQL =
            "SELECT table_name, comments FROM all_tab_comments " +
                    "WHERE owner = ?";

    private static final String COLUMN_SQL =
            "SELECT column_name, data_type, data_length, data_precision, data_scale, " +
                    "       nullable, data_default, column_id " +
                    "FROM all_tab_columns " +
                    "WHERE owner = ? AND table_name = ? " +
                    "ORDER BY column_id, column_name";

    private static final String COLUMN_COMMENT_SQL =
            "SELECT column_name, comments FROM all_col_comments " +
                    "WHERE owner = ? AND table_name = ?";

    private final EncryptionConfig encryptionConfig;

    public OracleMetadataExtractor(EncryptionConfig encryptionConfig) {
        this.encryptionConfig = encryptionConfig;
    }

    @Override
    public List<String> extractSchemas(DataSourceInfo ds) throws SQLException {
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
    public List<TableMetadata> extractTables(DataSourceInfo ds, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            schema = ds.getUsername().toUpperCase();
        }
        String owner = schema.toUpperCase();
        List<TableMetadata> tables = new ArrayList<>();
        Map<String, String> tableComments = new HashMap<>();
        try (Connection conn = openConnection(ds)) {
            try (PreparedStatement ps = conn.prepareStatement(TABLE_COMMENT_SQL)) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tableComments.put(rs.getString("table_name"), rs.getString("comments"));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(TABLE_SQL)) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TableMetadata table = new TableMetadata();
                        String tableName = rs.getString("table_name");
                        table.setDatabaseName(ds.getDatabaseName());
                        table.setSchemaName(owner);
                        table.setTableName(tableName);
                        table.setTableComment(tableComments.get(tableName));
                        table.setColumns(extractColumns(conn, owner, tableName));
                        tables.add(table);
                    }
                }
            }
        }
        return tables;
    }

    private List<ColumnMetadata> extractColumns(Connection conn, String owner, String tableName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        Map<String, String> columnComments = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(COLUMN_COMMENT_SQL)) {
            ps.setString(1, owner);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columnComments.put(rs.getString("column_name"), rs.getString("comments"));
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(COLUMN_SQL)) {
            ps.setString(1, owner);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMetadata col = new ColumnMetadata();
                    String columnName = rs.getString("column_name");
                    col.setColumnName(columnName);
                    col.setDataType(buildDataType(rs));
                    col.setColumnComment(columnComments.get(columnName));
                    col.setNullable("Y".equalsIgnoreCase(rs.getString("nullable")));
                    col.setColumnDefault(rs.getString("data_default"));
                    col.setOrdinalPosition(rs.getInt("column_id"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    private String buildDataType(ResultSet rs) throws SQLException {
        String type = rs.getString("data_type");
        if ("NUMBER".equalsIgnoreCase(type)) {
            int precision = rs.getInt("data_precision");
            int scale = rs.getInt("data_scale");
            if (!rs.wasNull() && precision > 0) {
                return scale > 0 || !rs.wasNull() && rs.getObject("data_scale") != null
                        ? String.format("NUMBER(%d,%d)", precision, scale)
                        : String.format("NUMBER(%d)", precision);
            }
            return "NUMBER";
        }
        if ("VARCHAR2".equalsIgnoreCase(type) || "CHAR".equalsIgnoreCase(type) || "NCHAR".equalsIgnoreCase(type) || "NVARCHAR2".equalsIgnoreCase(type)) {
            int length = rs.getInt("data_length");
            if (!rs.wasNull() && length > 0) {
                return String.format("%s(%d)", type, length);
            }
        }
        return type;
    }

    private Connection openConnection(DataSourceInfo ds) throws SQLException {
        String url = "jdbc:oracle:thin:@//" + ds.getHost() + ":" + ds.getPort() + "/" + ds.getDatabaseName();
        return DriverManager.getConnection(url, ds.getUsername(), encryptionConfig.decrypt(ds.getEncryptedPassword()));
    }
}
