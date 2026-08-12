package com.datanest.dataservice.service;

import com.datanest.common.constant.DataSourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.util.JdbcPreviewHelper;
import com.datanest.common.util.JdbcUrlBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部数据源只读 SQL 执行器（Sprint 10 F1）。
 * <p>
 * 执行任意只读 SQL（非固定 SELECT *），带 Statement.setQueryTimeout 超时中断（AC-2）。
 * URL 构造/值格式化/错误归类统一委托 common 的 {@link JdbcUrlBuilder}/{@link JdbcPreviewHelper}
 * （2026-08-12 收敛，原为逐字副本）。
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
        return query(type, host, port, database, schema, username, password, sql, timeoutSeconds, null);
    }

    /**
     * 外部数据源只读查询（Sprint 10 F1.1 取消支持）。
     * <p>
     * {@code connectionHandler} 在连接建立后、查询执行前被调用，供调用方注册
     * 「取消时关闭连接」的回调（SQL 终端「停止」按钮：interrupt + 关闭连接双管齐下）。
     *
     * @param connectionHandler 连接建立回调（可为 null）
     */
    public QueryResult query(String type, String host, int port,
                             String database, String schema,
                             String username, String password,
                             String sql, int timeoutSeconds,
                             java.util.function.Consumer<Connection> connectionHandler) {
        String url = buildJdbcUrl(type, host, port, database, schema);
        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement st = conn.createStatement()) {
            if (connectionHandler != null) {
                connectionHandler.accept(conn);
            }
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

    static boolean isTimeout(Throwable t) {
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

    static String buildJdbcUrl(String type, String host, int port, String database, String schema) {
        return buildJdbcUrl(type, host, port, database, schema, 10);
    }

    /**
     * 构建 JDBC URL（socket 超时可配，委托 common {@link JdbcUrlBuilder}）。
     *
     * @param socketTimeoutSeconds socket 超时秒数（默认 10，同步任务沿用；SQL 终端传入请求级超时，
     *                             避免 queryTimeout=60 被 10s socket 超时提前截断），上限 300s
     */
    static String buildJdbcUrl(String type, String host, int port, String database, String schema,
                               int socketTimeoutSeconds) {
        // 保持 DATASOURCE_UNSUPPORTED_TYPE 业务语义；URL 拼接统一委托共享实现
        if (DataSourceType.fromCode(type) == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_UNSUPPORTED_TYPE, "不支持的数据源类型: " + type);
        }
        return JdbcUrlBuilder.buildJdbcUrl(type, host, port, database, schema, socketTimeoutSeconds);
    }

    static Object formatValue(Object value) {
        return JdbcPreviewHelper.formatValue(value);
    }

    private static String classifyError(SQLException e) {
        return JdbcPreviewHelper.classifyError(e);
    }
}
