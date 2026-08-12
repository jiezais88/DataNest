package com.datanest.dataservice.service;

import com.datanest.common.constant.DataSourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部数据源只读 SQL 执行器（Sprint 10 F1）。
 * <p>
 * 对齐 JdbcPreviewHelper 的 URL 构造/值格式化/错误归类逻辑，但执行任意只读 SQL
 * （非固定 SELECT *），带 Statement.setQueryTimeout 超时中断（AC-2）。
 * 连接参数（type/host/port/database/schema/username/password）由 SqlQueryService
 * 从 DataSourceInfo + EncryptionConfig 解密后传入。
 */
@Service
public class ExternalSqlExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ExternalSqlExecutor.class);
    private static final int MAX_ROWS = 1000;

    public QueryResult query(String type, String host, int port,
                             String database, String schema,
                             String username, String password,
                             String sql, int timeoutSeconds) {
        String url = buildJdbcUrl(type, host, port, database, schema);
        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement st = conn.createStatement()) {
            if (timeoutSeconds > 0) {
                st.setQueryTimeout(timeoutSeconds);
            }
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int colCount = metaData.getColumnCount();
                List<String> columns = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>(colCount);
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), formatValue(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows, truncated);
            }
        } catch (Exception e) {
            if (isTimeout(e)) {
                logger.warn("外部数据源查询超时（{}s）: url={}", timeoutSeconds, url);
                throw new BusinessException(ErrorCode.SQL_TIMEOUT, "查询超时中断（" + timeoutSeconds + "s）");
            }
            logger.warn("外部数据源查询失败: url={}, error={}", url, e.getMessage());
            String message = e instanceof SQLException se ? classifyError(se) : e.getMessage();
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "查询失败: " + message);
        }
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }

    private static boolean isTimeout(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException se && "HYT00".equals(se.getSQLState())) {
                return true;
            }
            if (cur instanceof java.sql.SQLTimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.toLowerCase().contains("timeout") || msg.contains("超时"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String buildJdbcUrl(String type, String host, int port, String database, String schema) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_UNSUPPORTED_TYPE, "不支持的数据源类型: " + type);
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
            case ORACLE -> String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, database);
            case SQLSERVER -> String.format(
                    "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true;loginTimeout=10",
                    host, port, database);
        };
    }

    private static Object formatValue(Object value) {
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
        if (lower.contains("access denied") || lower.contains("authentication") || lower.contains("password")
                || lower.contains("28p01")) {
            return "认证失败，请检查用户名或密码";
        }
        if (lower.contains("unknown database") || lower.contains("does not exist") || lower.contains("3d000")) {
            return "数据库或表不存在";
        }
        return message;
    }
}
