package com.datanest.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
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
        String dataSql = "SELECT * FROM " + qualifiedTable
                + " ORDER BY 1 LIMIT " + Math.max(1, Math.min(limit, 1000));
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
                        row.put(columns.get(i - 1), rs.getObject(i));
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

    private static String buildJdbcUrl(String type, String host, int port, String database, String schema) {
        return switch (type) {
            case "MYSQL", "DORIS" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                    host, port, database);
            case "POSTGRESQL" -> {
                if (schema != null && !schema.isBlank()) {
                    yield String.format(
                            "jdbc:postgresql://%s:%d/%s?currentSchema=%s&connectTimeout=10&socketTimeout=10",
                            host, port, database, schema);
                }
                yield String.format(
                        "jdbc:postgresql://%s:%d/%s?connectTimeout=10&socketTimeout=10",
                        host, port, database);
            }
            default -> throw new IllegalArgumentException("Unsupported data source type: " + type);
        };
    }

    private static String buildQualifiedTableName(String type, String database, String schema, String tableName) {
        if (tableName.contains(".")) {
            return escapeIdentifier(type, tableName);
        }
        return switch (type) {
            case "POSTGRESQL" -> {
                if (schema != null && !schema.isBlank()) {
                    yield escapeIdentifier(type, schema) + "." + escapeIdentifier(type, tableName);
                }
                yield escapeIdentifier(type, tableName);
            }
            case "MYSQL", "DORIS" -> {
                if (database != null && !database.isBlank()) {
                    yield escapeIdentifier(type, database) + "." + escapeIdentifier(type, tableName);
                }
                yield escapeIdentifier(type, tableName);
            }
            default -> escapeIdentifier(type, tableName);
        };
    }

    private static String escapeIdentifier(String type, String name) {
        return switch (type) {
            case "POSTGRESQL" -> "\"" + name.replace("\"", "\"\"") + "\"";
            case "MYSQL", "DORIS" -> "`" + name.replace("`", "``") + "`";
            default -> "`" + name.replace("`", "``") + "`";
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
        if (lower.contains("unknown database") || lower.contains("does not exist") || lower.contains("3d000")) {
            return "数据库或表不存在";
        }
        return "查询失败: " + message;
    }

    public record PreviewResult(List<String> columns, List<Map<String, Object>> rows, int rowCount,
                                long totalRowCount) {
    }
}
