package com.datanest.common.util;

import com.datanest.common.constant.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨服务复用的 JDBC Schema 拉取工具，不依赖具体工程实体。
 */
public final class JdbcSchemaExtractor {

    private static final Logger logger = LoggerFactory.getLogger(JdbcSchemaExtractor.class);

    private JdbcSchemaExtractor() {
    }

    public static List<String> extractSchemas(String type, String host, int port,
                                              String database, String schema,
                                              String username, String password) {
        String url = buildJdbcUrl(type, host, port, database, schema);

        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(schemaListSql(type))) {

            List<String> schemas = new ArrayList<>();
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
            return schemas;
        } catch (SQLException e) {
            logger.warn("Failed to extract schemas: url={}, error={}", url, e.getMessage());
            throw new IllegalStateException("拉取库/Schema 失败: " + classifyError(e), e);
        }
    }

    public static List<String> extractDatabases(String type, String host, int port,
                                                String database, String schema,
                                                String username, String password) {
        // MySQL / Doris 的 database 等价于 PostgreSQL 的 schema，统一按 catalog/schema 语义返回可访问的库/模式
        return extractSchemas(type, host, port, database, schema, username, password);
    }

    public static List<String> extractTables(String type, String host, int port,
                                             String database, String schema,
                                             String username, String password) {
        String url = buildJdbcUrl(type, host, port, database, schema);
        String effectiveSchema = resolveEffectiveSchema(type, database, schema);

        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement ps = conn.prepareStatement(tableListSql(type))) {
            ps.setString(1, effectiveSchema);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                return tables;
            }
        } catch (SQLException e) {
            logger.warn("Failed to extract tables: url={}, schema={}, error={}", url, effectiveSchema, e.getMessage());
            throw new IllegalStateException("拉取表列表失败: " + classifyError(e), e);
        }
    }

    public static List<ColumnInfo> extractColumns(String type, String host, int port,
                                                  String database, String schema,
                                                  String username, String password,
                                                  String tableName) {
        String url = buildJdbcUrl(type, host, port, database, schema);
        String effectiveSchema = resolveEffectiveSchema(type, database, schema);

        try (Connection conn = DriverManager.getConnection(url, username, password);
             ResultSet rs = conn.getMetaData().getColumns(database, effectiveSchema, tableName, null)) {
            List<ColumnInfo> columns = new ArrayList<>();
            while (rs.next()) {
                ColumnInfo info = new ColumnInfo(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getString("REMARKS"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        rs.getString("COLUMN_DEF"),
                        rs.getInt("ORDINAL_POSITION")
                );
                columns.add(info);
            }
            columns.sort((a, b) -> Integer.compare(a.ordinalPosition(), b.ordinalPosition()));
            return columns;
        } catch (SQLException e) {
            logger.warn("Failed to extract columns: url={}, schema={}, table={}, error={}",
                    url, effectiveSchema, tableName, e.getMessage());
            throw new IllegalStateException("拉取字段列表失败: " + classifyError(e), e);
        }
    }

    public record ColumnInfo(String columnName, String dataType, String columnComment,
                             Boolean nullable, String columnDefault, Integer ordinalPosition) {
    }

    private static String buildJdbcUrl(String type, String host, int port, String database, String schema) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                    host, port, database);
            case DORIS -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                    host, port, database);
            case POSTGRESQL -> {
                if (schema != null && !schema.isBlank()) {
                    yield String.format(
                            "jdbc:postgresql://%s:%d/%s?currentSchema=%s&connectTimeout=10&socketTimeout=10",
                            host, port, database, schema);
                }
                yield String.format(
                        "jdbc:postgresql://%s:%d/%s?connectTimeout=10&socketTimeout=10",
                        host, port, database);
            }
            case ORACLE -> String.format(
                    "jdbc:oracle:thin:@//%s:%d/%s",
                    host, port, database);
            case SQLSERVER -> String.format(
                    "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true;loginTimeout=10",
                    host, port, database);
        };
    }

    private static String schemaListSql(String type) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL, DORIS -> """
                    SELECT schema_name FROM information_schema.schemata
                    WHERE schema_name NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                    ORDER BY schema_name
                    """;
            case POSTGRESQL -> """
                    SELECT schema_name FROM information_schema.schemata
                    WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                    AND schema_name NOT LIKE 'pg_%' ORDER BY schema_name
                    """;
            case ORACLE -> """
                    SELECT username FROM all_users
                    WHERE username NOT IN ('SYS','SYSTEM','OUTLN','DBSNMP','APPQOSSYS','CTXSYS','MDSYS','OLAPSYS','ORDDATA','ORDPLUGINS','ORDSYS','SI_INFORMTN_SCHEMA','XDB','XS$NULL','WMSYS','ANONYMOUS','DIP','ORACLE_OCM','ORDSYS')
                    ORDER BY username
                    """;
            case SQLSERVER -> """
                    SELECT name FROM sys.schemas
                    WHERE name NOT IN ('sys', 'information_schema', 'guest')
                    ORDER BY name
                    """;
        };
    }

    private static String tableListSql(String type) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL, DORIS -> """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = ? AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                    """;
            case POSTGRESQL -> """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = ? AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                    """;
            case ORACLE -> """
                    SELECT table_name FROM all_tables
                    WHERE owner = ?
                    ORDER BY table_name
                    """;
            case SQLSERVER -> """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = ? AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                    """;
        };
    }

    private static String resolveEffectiveSchema(String type, String database, String schema) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case POSTGRESQL -> schema != null && !schema.isBlank() ? schema : "public";
            case ORACLE -> schema != null && !schema.isBlank() ? schema : null;
            case SQLSERVER -> schema != null && !schema.isBlank() ? schema : "dbo";
            case MYSQL, DORIS -> database != null && !database.isBlank() ? database : schema;
        };
    }

    private static String classifyError(SQLException e) {
        String sqlState = e.getSQLState();
        String message = e.getMessage();
        if (message == null) {
            message = "";
        }

        String lower = message.toLowerCase();

        if (sqlState != null && sqlState.startsWith("08")) {
            return "连接超时或目标不可达，请检查主机和端口";
        }

        if (lower.contains("access denied") || lower.contains("authentication") || lower.contains("password") || lower.contains("28p01")) {
            return "认证失败，请检查用户名或密码";
        }

        if (lower.contains("unknown database") || lower.contains("database \"") || lower.contains("does not exist") || lower.contains("3d000")) {
            return "数据库不存在，请检查数据库名";
        }

        return "连接失败: " + message;
    }
}
