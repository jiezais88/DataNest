package com.datanest.task.core.service;

import com.datanest.common.util.JdbcSchemaExtractor;
import com.datanest.task.core.dto.TestConnectionRequest;
import com.datanest.task.core.dto.TestConnectionResult;
import com.datanest.task.core.entity.DataSourceConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

@Component
public class ConnectionTester {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionTester.class);

    public TestConnectionResult test(TestConnectionRequest request) {
        String url = buildJdbcUrl(request);
        String username = request.getUsername();
        String password = request.getPassword();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            if (connection.isValid(10)) {
                return new TestConnectionResult(true, "连接成功");
            }
            return new TestConnectionResult(false, "连接无效");
        } catch (SQLException e) {
            logger.warn("Data source connection test failed: url={}, error={}", url, e.getMessage());
            String message = classifyError(e);
            return new TestConnectionResult(false, message);
        } catch (Exception e) {
            logger.error("Unexpected error during connection test: url={}", url, e);
            return new TestConnectionResult(false, "连接异常: " + e.getMessage());
        }
    }

    public List<String> extractSchemas(DataSourceConnection connection, String decryptedPassword) {
        return JdbcSchemaExtractor.extractSchemas(
                connection.getType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getSchemaName(),
                connection.getUsername(),
                decryptedPassword
        );
    }

    public List<String> extractDatabases(DataSourceConnection connection, String decryptedPassword) {
        return JdbcSchemaExtractor.extractDatabases(
                connection.getType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getSchemaName(),
                connection.getUsername(),
                decryptedPassword
        );
    }

    public List<String> extractTables(DataSourceConnection connection, String decryptedPassword,
                                      String database, String schema) {
        return JdbcSchemaExtractor.extractTables(
                connection.getType(),
                connection.getHost(),
                connection.getPort(),
                database,
                schema,
                connection.getUsername(),
                decryptedPassword
        );
    }

    public String buildJdbcUrl(DataSourceConnection connection) {
        return buildJdbcUrl(connection.getType(), connection.getHost(), connection.getPort(),
                connection.getDatabaseName(), connection.getSchemaName());
    }

    private String buildJdbcUrl(TestConnectionRequest request) {
        return buildJdbcUrl(request.getType(), request.getHost(), request.getPort(), request.getDatabaseName(), request.getSchemaName());
    }

    private String buildJdbcUrl(String type, String host, int port, String database, String schema) {
        return switch (type) {
            case "MYSQL" ->
                    String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000", host, port, database);
            case "DORIS" ->
                    String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000", host, port, database);
            case "POSTGRESQL" -> {
                if (schema != null && !schema.isBlank()) {
                    yield String.format("jdbc:postgresql://%s:%d/%s?currentSchema=%s&connectTimeout=10&socketTimeout=10", host, port, database, schema);
                }
                yield String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=10&socketTimeout=10", host, port, database);
            }
            default -> throw new IllegalArgumentException("Unsupported data source type: " + type);
        };
    }

    private String classifyError(SQLException e) {
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
