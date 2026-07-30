package com.datanest.task.core.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Doris SQL 执行器
 * 用例：dag_node SQL 类型节点回调时调 execute() 执行用户写的 SQL
 * 注意：Doris MySQL 协议，jdbc 用 mysql-connector-j
 *
 * Sprint 3 P1-4：改用 HikariCP DataSource（注入名为 "dorisDataSource" 的 Bean）
 * 替换原来的 DriverManager.getConnection 每次新建连接
 */
@Service
public class DorisSqlExecutor {

    private static final Logger logger = LoggerFactory.getLogger(DorisSqlExecutor.class);

    private final DataSource dataSource;

    public DorisSqlExecutor(@Qualifier("dorisDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 执行 SQL（不返回结果集），返回影响行数
     * 多个 SQL 用 ; 分隔依次执行
     */
    public int execute(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 不能为空");
        }
        try (Connection conn = dataSource.getConnection()) {
            int totalAffected = 0;
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) continue;
                try (Statement st = conn.createStatement()) {
                    int affected = st.executeUpdate(trimmed);
                    totalAffected += Math.max(affected, 0);
                }
            }
            return totalAffected;
        } catch (Exception e) {
            logger.error("Doris SQL 执行失败: sql={}", sql, e);
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 执行失败: " + e.getMessage());
        }
    }

    /**
     * 执行查询 SQL，返回列头 + 行数据
     */
    public QueryResult query(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 不能为空");
        }
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
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
        } catch (Exception e) {
            logger.error("Doris SQL 查询失败: sql={}", sql, e);
            throw new BusinessException(ErrorCode.SQL_EXECUTE_FAILED, "SQL 查询失败: " + e.getMessage());
        }
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }
}
