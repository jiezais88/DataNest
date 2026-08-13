package com.datanest.dataservice.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.config.DorisDataSourceConfig;
import com.datanest.task.core.service.DorisSqlExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 可取消 SQL 执行器（Sprint 10 F1.1，SQL 终端「停止」按钮支持）。
 * <p>
 * 与 {@link ExternalSqlExecutor}/{@code DorisSqlExecutor} 的区别：查询执行期间把打开的
 * Connection 通过回调暴露给调用方（SqlQueryService），「停止」时先 interrupt 执行线程、
 * 再关闭连接，让 JDBC 驱动的阻塞读取立即中断（仅 setQueryTimeout 无法提前终止）。
 * <p>
 * 2026-08-12 收敛：Doris 连接获取直接复用 task-core {@link DorisSqlExecutor#openConnection}
 * （原为逐字副本，HikariCP 优先 + DriverManager 降级）；JDBC URL 构造与值格式化复用
 * 同包 ExternalSqlExecutor 的 package-private static 方法（其底层委托 common）。
 */
@Service
public class CancelableSqlExecutor {

    private static final Logger logger = LoggerFactory.getLogger(CancelableSqlExecutor.class);
    private static final int MAX_ROWS = 1000;

    private final DorisSqlExecutor dorisSqlExecutor;

    public CancelableSqlExecutor(DorisSqlExecutor dorisSqlExecutor) {
        this.dorisSqlExecutor = dorisSqlExecutor;
    }

    /**
     * 外部数据源查询（连接建立后回调 {@code onOpen}，供取消时关闭连接）。
     */
    public QueryResult queryExternal(String type, String host, int port,
                                     String database, String schema,
                                     String username, String password,
                                     String sql, int timeoutSeconds,
                                     Consumer<Connection> onOpen) {
        String url = ExternalSqlExecutor.buildJdbcUrl(type, host, port, database, schema, timeoutSeconds);
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            if (onOpen != null) {
                onOpen.accept(conn);
            }
            return executeOnConnection(conn, sql, timeoutSeconds, url);
        } catch (Exception e) {
            throw translate(url, timeoutSeconds, e);
        }
    }

    /**
     * 内置 Doris 查询（连接建立后回调 {@code onOpen}，供取消时关闭连接）。
     */
    public QueryResult queryDoris(String sql, int timeoutSeconds, Consumer<Connection> onOpen) {
        String url = "doris(" + DorisDataSourceConfig.currentDatabase() + ")";
        try (Connection conn = openDorisConnection()) {
            if (onOpen != null) {
                onOpen.accept(conn);
            }
            return executeOnConnection(conn, sql, timeoutSeconds, url);
        } catch (Exception e) {
            throw translate(url, timeoutSeconds, e);
        }
    }

    private QueryResult executeOnConnection(Connection conn, String sql, int timeoutSeconds, String url) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (timeoutSeconds > 0) {
                st.setQueryTimeout(timeoutSeconds);
            }
            try (ResultSet rs = st.executeQuery(sql)) {
                return collect(rs);
            }
        }
    }

    /**
     * 带参数的外部数据源查询（对外 API 参数化筛选，PreparedStatement 绑定防注入）。
     */
    public QueryResult queryExternal(String type, String host, int port,
                                     String database, String schema,
                                     String username, String password,
                                     String sql, List<Object> params, int timeoutSeconds) {
        String url = ExternalSqlExecutor.buildJdbcUrl(type, host, port, database, schema, timeoutSeconds);
        try (Connection conn = DriverManager.getConnection(url, username, password)) {
            return executeWithParams(conn, sql, params, timeoutSeconds);
        } catch (Exception e) {
            throw translate(url, timeoutSeconds, e);
        }
    }

    /**
     * 带参数的内置 Doris 查询（对外 API 参数化筛选）。
     */
    public QueryResult queryDoris(String sql, List<Object> params, int timeoutSeconds) {
        String url = "doris(" + DorisDataSourceConfig.currentDatabase() + ")";
        try (Connection conn = openDorisConnection()) {
            return executeWithParams(conn, sql, params, timeoutSeconds);
        } catch (Exception e) {
            throw translate(url, timeoutSeconds, e);
        }
    }

    private QueryResult executeWithParams(Connection conn, String sql, List<Object> params,
                                          int timeoutSeconds) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (timeoutSeconds > 0) {
                ps.setQueryTimeout(timeoutSeconds);
            }
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                return collect(rs);
            }
        }
    }

    /** 统一结果提取（列头 + 行数据 + 1000 行截断），Statement/PreparedStatement 共用 */
    private QueryResult collect(ResultSet rs) throws SQLException {
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
                row.put(columns.get(i - 1), ExternalSqlExecutor.formatValue(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, truncated);
    }

    /**
     * Doris 连接获取：直接复用 task-core {@link DorisSqlExecutor#openConnection}
     * （HikariCP 连接池优先，不可达降级 DriverManager）。
     */
    private Connection openDorisConnection() throws SQLException {
        return dorisSqlExecutor.openConnection();
    }

    private BusinessException translate(String url, int timeoutSeconds, Exception e) {
        if (ExternalSqlExecutor.isTimeout(e)) {
            logger.warn("SQL 查询超时（{}s）: url={}", timeoutSeconds, url);
            return new BusinessException(ErrorCode.SQL_TIMEOUT, "查询超时中断（" + timeoutSeconds + "s）");
        }
        logger.warn("SQL 查询失败: url={}, error={}", url, e.getMessage());
        String message = e instanceof SQLException se && !"已停止".equals(se.getMessage())
                ? se.getMessage() : e.getMessage();
        return new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "查询失败: " + message);
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }
}
