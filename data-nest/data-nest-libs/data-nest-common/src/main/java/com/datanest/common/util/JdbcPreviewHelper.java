package com.datanest.common.util;

import com.datanest.common.constant.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨服务复用的 JDBC 数据预览工具。
 */
public final class JdbcPreviewHelper {

    private static final Logger logger = LoggerFactory.getLogger(JdbcPreviewHelper.class);
    private static final int DEFAULT_LIMIT = 100;
    private static final String IDENTIFIER_PATTERN = "^[a-zA-Z0-9_$.]+$";

    private JdbcPreviewHelper() {
    }

    public static PreviewResult preview(String type, String host, int port,
                                        String database, String schema,
                                        String username, String password,
                                        String tableName) {
        return preview(type, host, port, database, schema, username, password, tableName, DEFAULT_LIMIT);
    }

    public static PreviewResult preview(String type, String host, int port,
                                        String database, String schema,
                                        String username, String password,
                                        String tableName, int limit) {
        validateIdentifier("数据库名", database);
        validateIdentifier("Schema名", schema);
        validateIdentifier("表名", tableName);

        String url = buildJdbcUrl(type, host, port, database, schema);
        String qualifiedTable = buildQualifiedTableName(type, database, schema, tableName);
        String dataSql = buildPreviewSql(type, qualifiedTable, Math.max(1, Math.min(limit, 1000)));
        String countSql = "SELECT COUNT(*) FROM " + qualifiedTable;

        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement()) {

            long totalRowCount = 0L;
            try (ResultSet countRs = stmt.executeQuery(countSql)) {
                if (countRs.next()) {
                    totalRowCount = countRs.getLong(1);
                }
            }

            try (ResultSet rs = stmt.executeQuery(dataSql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                int rowCount = 0;
                while (rs.next() && rowCount < limit) {
                    Map<String, Object> row = new LinkedHashMap<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(columns.get(i - 1), formatValue(rs.getObject(i)));
                    }
                    rows.add(row);
                    rowCount++;
                }
                return new PreviewResult(columns, rows, rowCount, totalRowCount);
            }
        } catch (SQLException e) {
            logger.warn("Failed to preview table: url={}, table={}, error={}", url, qualifiedTable, e.getMessage());
            throw new IllegalStateException("数据预览失败: " + classifyError(e), e);
        }
    }

    private static void validateIdentifier(String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(label + "包含非法字符: " + value);
        }
    }

    /**
     * JDBC 结果值统一格式化（Timestamp/Date/Time/LocalDateTime/Oracle 内部类型 → 标准字符串）。
     * <p>
     * 2026-08-12 提升可见性：供 data-service ExternalSqlExecutor 复用（原为 private 副本）。
     */
    public static Object formatValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof LocalDate ld) {
            return ld.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if (value instanceof LocalTime lt) {
            return lt.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
        // Oracle TIMESTAMP/DATE are returned as oracle.sql.TIMESTAMP which is not a standard JDBC type.
        String className = value.getClass().getName();
        if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.DATE".equals(className)) {
            try {
                Timestamp ts = (Timestamp) value.getClass().getMethod("timestampValue").invoke(value);
                return ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                logger.warn("Failed to convert Oracle timestamp: {}", value);
                return value.toString();
            }
        }
        return value;
    }

    private static String buildPreviewSql(String type, String qualifiedTable, int limit) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        String orderBy = " ORDER BY 1";
        return switch (dataSourceType) {
            case MYSQL, DORIS, POSTGRESQL -> "SELECT * FROM " + qualifiedTable + orderBy + " LIMIT " + limit;
            case ORACLE -> "SELECT * FROM " + qualifiedTable + orderBy + " FETCH FIRST " + limit + " ROWS ONLY";
            case SQLSERVER ->
                    "SELECT * FROM " + qualifiedTable + orderBy + " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY";
        };
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

    private static String buildQualifiedTableName(String type, String database, String schema, String tableName) {
        if (tableName.contains(".")) {
            return escapeIdentifier(type, tableName);
        }
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case POSTGRESQL -> {
                if (schema != null && !schema.isBlank()) {
                    yield escapeIdentifier(type, schema) + "." + escapeIdentifier(type, tableName);
                }
                yield escapeIdentifier(type, tableName);
            }
            case MYSQL, DORIS -> {
                if (database != null && !database.isBlank()) {
                    yield escapeIdentifier(type, database) + "." + escapeIdentifier(type, tableName);
                }
                yield escapeIdentifier(type, tableName);
            }
            case ORACLE -> {
                if (schema != null && !schema.isBlank()) {
                    yield escapeIdentifier(type, schema) + "." + escapeIdentifier(type, tableName);
                }
                yield escapeIdentifier(type, tableName);
            }
            case SQLSERVER -> {
                if (schema != null && !schema.isBlank()) {
                    yield escapeIdentifier(type, schema) + "." + escapeIdentifier(type, tableName);
                }
                yield escapeIdentifier(type, tableName);
            }
        };
    }

    private static String escapeIdentifier(String type, String name) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case POSTGRESQL, ORACLE -> "\"" + name.replace("\"", "\"\"") + "\"";
            case MYSQL, DORIS -> "`" + name.replace("`", "``") + "`";
            case SQLSERVER -> "[" + name.replace("]", "]]") + "]";
        };
    }

    /**
     * SQL 异常友好分类（连接失败/认证失败/库表不存在）。
     * <p>
     * 2026-08-12 提升可见性：供 data-service ExternalSqlExecutor 复用（原为 private 副本）。
     */
    public static String classifyError(SQLException e) {
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
        if (lower.contains("unknown database") || lower.contains("does not exist") || lower.contains("3d000")) {
            return "数据库或表不存在";
        }
        // 2026-08-12 统一：返回裸消息，由调用方拼前缀（preview 拼「数据预览失败: 」；
        // data-service ExternalSqlExecutor 拼「查询失败: 」，避免双重前缀）
        return message;
    }

    public record PreviewResult(List<String> columns, List<Map<String, Object>> rows, int rowCount,
                                long totalRowCount) {
    }
}
