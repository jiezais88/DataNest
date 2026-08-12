package com.datanest.task.core.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.config.DorisDataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.datanest.task.core.service.SqlStatementSplitter.split;

/**
 * Doris SQL 执行器
 * 用例：dag_node SQL 类型节点回调时调 execute() 执行用户写的 SQL
 * 注意：Doris MySQL 协议，jdbc 用 mysql-connector-j
 *
 * Sprint 3 P1-4：尝试用 HikariCP 连接池（dorisDataSource）
 * - 连接池可达 → 复用连接
 * - 不可达 → 降级到 DriverManager.getConnection（每次新建，结束时关闭）
 * 这样 Spring 启动不被 doris 可达性绑定
 */
@Service
public class DorisSqlExecutor {

    private static final Logger logger = LoggerFactory.getLogger(DorisSqlExecutor.class);

    public DorisSqlExecutor() {
    }

    /**
     * 拿 connection：先试连接池，失败则 DriverManager
     */
    private Connection openConnection() throws java.sql.SQLException {
        javax.sql.DataSource ds = DorisDataSourceConfig.getDataSource();
        if (ds != null) {
            return ds.getConnection();
        }
        // 降级：从 Spring 配置里拿（用反射或读 system property；这里简化用环境变量）
        String host = System.getProperty("datanest.doris.fe-host",
                System.getenv().getOrDefault("DORIS_FE_HOST", "localhost"));
        String portStr = System.getProperty("datanest.doris.fe-query-port",
                System.getenv().getOrDefault("DORIS_FE_PORT", "9030"));
        String user = System.getProperty("datanest.doris.user",
                System.getenv().getOrDefault("DORIS_USER", "root"));
        String password = System.getProperty("datanest.doris.password",
                System.getenv().getOrDefault("DORIS_PASSWORD", ""));
        String database = System.getProperty("datanest.engineering.addax.target-database",
                System.getenv().getOrDefault("DORIS_DATABASE", "datanest"));
        String url = String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000",
                host, portStr, database);
        logger.debug("Doris 连接池不可用，降级到 DriverManager: {}", url);
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 执行 SQL（兼容 DDL/DML/SELECT），返回影响行数（SELECT 返回行数，DML 返回 changed rows，DDL 返回 0）
     * 多个 SQL 用 ; 分隔依次执行
     * 决策 Sprint3-Fix：用 execute() 自动判断是否有 ResultSet，避免 SELECT 走 executeUpdate 抛
     * "Statement.executeUpdate() cannot issue statements that produce result sets"
     */
    public int execute(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 不能为空");
        }
        try (Connection conn = openConnection()) {
            int totalAffected = 0;
            for (String stmt : split(sql)) {
                if (stmt.isEmpty()) continue;
                try (Statement st = conn.createStatement()) {
                    boolean hasResultSet = st.execute(stmt);
                    if (hasResultSet) {
                        // SELECT 等：消费结果集让连接回归干净状态
                        try (ResultSet rs = st.getResultSet()) {
                            int rowCount = 0;
                            while (rs.next()) rowCount++;
                            totalAffected += rowCount;
                        }
                    } else {
                        // DDL/DML：getUpdateCount 拿受影响行
                        totalAffected += Math.max(st.getUpdateCount(), 0);
                    }
                }
            }
            return totalAffected;
        } catch (Exception e) {
            logger.error("Doris SQL 执行失败: sql={}", sql, e);
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 执行失败: " + e.getMessage());
        }
    }

    /**
     * 执行查询 SQL，返回列头 + 行数据（无超时限制）。
     */
    public QueryResult query(String sql) {
        return query(sql, 0);
    }

    /**
     * 执行查询 SQL，返回列头 + 行数据（Sprint 10 F1 数据服务 SQL 终端超时中断）。
     * <p>
     * timeoutSeconds &gt; 0 时对 Statement 设 setQueryTimeout（MySQL/Doris 驱动按秒超时，
     * 超时抛 SQLTimeoutException，由上层转 SQL_TIMEOUT 语义）；&lt;= 0 表示不设超时（兼容原行为）。
     */
    public QueryResult query(String sql, int timeoutSeconds) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 不能为空");
        }
        try (Connection conn = openConnection();
             Statement st = conn.createStatement()) {
            if (timeoutSeconds > 0) {
                st.setQueryTimeout(timeoutSeconds);
            }
            try (ResultSet rs = st.executeQuery(sql)) {
                int colCount = rs.getMetaData().getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(rs.getMetaData().getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                int maxRows = 1000;
                while (rs.next() && rows.size() < maxRows) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows, rows.size() >= maxRows);
            }
        } catch (Exception e) {
            // Sprint 10 F1：Statement.setQueryTimeout 超时（JDBC 驱动抛 SQLTimeoutException 或
            // 消息含 timeout 的 SQLException），映射为 SQL_TIMEOUT 语义，数据服务可明确提示「查询超时中断」
            if (isTimeout(e)) {
                logger.warn("Doris SQL 查询超时（{}s）: sql={}", timeoutSeconds, sql);
                throw new BusinessException(ErrorCode.SQL_TIMEOUT, "查询超时中断（" + timeoutSeconds + "s）");
            }
            logger.error("Doris SQL 查询失败: sql={}", sql, e);
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 查询失败: " + e.getMessage());
        }
    }

    /** 递归判断异常是否为查询超时（SQLTimeoutException 或消息含 timeout/超时） */
    private static boolean isTimeout(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
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

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }
}
