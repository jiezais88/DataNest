package com.datanest.engineering.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.mapper.DataSourceConnectionMapper;
import com.datanest.task.core.service.ConnectionTester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic SQL executor for any registered data source.
 * <p>
 * Difference vs {@code com.datanest.task.core.service.DorisSqlExecutor}:
 * DorisSqlExecutor is hard-wired to built-in Doris (used by DS callback path).
 * GenericSqlExecutor accepts a DataSourceConnection, builds the JDBC URL from
 * type/host/port/database/user/pass and opens a fresh connection via DriverManager.
 * <p>
 * Per-statement single-shot: caller is responsible for SQL splitting. One stmt in,
 * one PreviewResult out. No exception is thrown on failure - status=FAILED + error
 * field. (DorisSqlExecutor.execute throws BusinessException, but that path expects
 * "all or nothing"; SQL preview needs "one failure doesn't block the rest".)
 * <p>
 * Timeout: Statement.setQueryTimeout(5) - prevents SELECT on huge table from
 * pinning the HTTP thread.
 */
@Service
public class GenericSqlExecutor {

    private static final Logger logger = LoggerFactory.getLogger(GenericSqlExecutor.class);
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int PREVIEW_MAX_ROWS = 200;

    private final DataSourceConnectionMapper dataSourceMapper;
    private final EncryptionConfig encryptionConfig;
    private final ConnectionTester connectionTester;

    public GenericSqlExecutor(DataSourceConnectionMapper dataSourceMapper,
                              EncryptionConfig encryptionConfig,
                              ConnectionTester connectionTester) {
        this.dataSourceMapper = dataSourceMapper;
        this.encryptionConfig = encryptionConfig;
        this.connectionTester = connectionTester;
    }

    public DataSourceConnection getDataSource(Long datasourceId) {
        DataSourceConnection ds = dataSourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND);
        }
        return ds;
    }

    public PreviewResult execute(DataSourceConnection ds, String sql) {
        String password = encryptionConfig.decrypt(ds.getEncryptedPassword());
        String url = connectionTester.buildJdbcUrl(
                ds.getType(), ds.getHost(), ds.getPort(),
                ds.getDatabaseName(), ds.getSchemaName());

        try (Connection conn = DriverManager.getConnection(url, ds.getUsername(), password);
             Statement st = conn.createStatement()) {

            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            boolean hasResultSet = st.execute(sql);
            if (hasResultSet) {
                return readQueryResult(st, sql);
            } else {
                int updateCount = Math.max(st.getUpdateCount(), 0);
                String type = classifyDmlDdl(sql);
                return new PreviewResult(
                        type,
                        updateCount,
                        null,
                        null,
                        describeDmlDdl(type, updateCount, sql),
                        null
                );
            }
        } catch (SQLException e) {
            logger.warn("SQL preview execution failed: url={}, sql={}, error={}", url, sql, e.getMessage());
            return PreviewResult.failed(classifyByError(e), e.getMessage());
        } catch (Exception e) {
            logger.error("SQL preview exception: url={}, sql={}", url, sql, e);
            return PreviewResult.failed("UNKNOWN", e.getMessage());
        }
    }

    private PreviewResult readQueryResult(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.getResultSet()) {
            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();
            List<String> columns = new ArrayList<>(colCount);
            List<String> columnTypes = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                columns.add(md.getColumnLabel(i));
                columnTypes.add(md.getColumnTypeName(i));
            }
            List<List<Object>> rows = new ArrayList<>();
            int rowCount = 0;
            while (rs.next() && rows.size() < PREVIEW_MAX_ROWS) {
                List<Object> row = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    row.add(readCell(rs, i, columnTypes.get(i - 1)));
                }
                rows.add(row);
                rowCount++;
            }
            String msg;
            boolean truncated = rowCount >= PREVIEW_MAX_ROWS;
            if (truncated) {
                msg = String.format("Query returned at least %d rows (truncated, max %d displayed)",
                        PREVIEW_MAX_ROWS, PREVIEW_MAX_ROWS);
            } else {
                msg = String.format("Query returned %d row(s)", rowCount);
            }
            return new PreviewResult("QUERY", rowCount, columns, rows, msg, null, truncated);
        }
    }

    /**
     * Per-cell type-aware read. Oracle TIMESTAMP returns oracle.sql.TIMESTAMP via
     * getObject() which fastjson2 cannot serialize cleanly (raw byte buffer shows
     * up in JSON). Use getObject(idx, LocalDateTime.class) for temporal types.
     */
    private Object readCell(ResultSet rs, int idx, String typeName) {
        try {
            String upper = typeName == null ? "" : typeName.toUpperCase();
            if (upper.contains("TIMESTAMP") || upper.contains("DATE")) {
                java.time.LocalDateTime ldt = rs.getObject(idx, java.time.LocalDateTime.class);
                return ldt == null ? null : ldt.toString();
            }
            if (upper.contains("TIME")) {
                java.time.LocalTime lt = rs.getObject(idx, java.time.LocalTime.class);
                return lt == null ? null : lt.toString();
            }
            Object o = rs.getObject(idx);
            if (o != null && o.getClass().getName().startsWith("oracle.sql.")) {
                // Fallback: convert via toString to avoid leaking Oracle internal types
                return o.toString();
            }
            return o;
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Simple DML/DDL classification: only inspects the first keyword. Complex PL/SQL
     * may be misclassified but that does not affect actual execution in preview mode.
     */
    private String classifyDmlDdl(String sql) {
        String trimmed = sql.trim();
        int firstSpace = trimmed.indexOf(' ');
        String first = firstSpace > 0 ? trimmed.substring(0, firstSpace) : trimmed;
        String upper = first.toUpperCase();
        if (upper.startsWith("CREATE") || upper.startsWith("DROP") || upper.startsWith("ALTER")
                || upper.startsWith("TRUNCATE") || upper.startsWith("RENAME") || upper.startsWith("COMMENT")) {
            return "DDL";
        }
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE")
                || upper.startsWith("MERGE")) {
            return "DML";
        }
        return "UNKNOWN";
    }

    private String describeDmlDdl(String type, int affected, String sql) {
        if ("DDL".equals(type)) {
            String trimmed = sql.trim();
            String[] tokens = trimmed.split("\\s+", 4);
            if (tokens.length >= 3) {
                return String.format("Executed %s %s", tokens[0].toUpperCase(), tokens[2]);
            }
            return "DDL executed";
        }
        if ("DML".equals(type)) {
            return String.format("Affected %d row(s)", affected);
        }
        return "Success";
    }

    private String classifyByError(SQLException e) {
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith("42")) {
            return "SQL_PARSE_FAILED";
        }
        if (sqlState != null && sqlState.startsWith("08")) {
            return "CONNECTION_FAILED";
        }
        if (sqlState != null && sqlState.startsWith("28")) {
            return "AUTH_FAILED";
        }
        return "SQL_PREVIEW_FAILED";
    }

    /**
     * Soft-failure result wrapper: caller renders by fields, no exception thrown.
     */
    public static class PreviewResult {
        public final String type;
        public final int rowCount;
        public final List<String> columns;
        public final List<List<Object>> rows;
        public final String message;
        public final String error;
        public final boolean success;
        /** QUERY 结果集是否被 PREVIEW_MAX_ROWS 截断；非 QUERY / 失败恒为 false */
        public final boolean truncated;

        private PreviewResult(String type, int rowCount, List<String> columns, List<List<Object>> rows,
                              String message, String error) {
            this(type, rowCount, columns, rows, message, error, false);
        }

        private PreviewResult(String type, int rowCount, List<String> columns, List<List<Object>> rows,
                              String message, String error, boolean truncated) {
            this.type = type;
            this.rowCount = rowCount;
            this.columns = columns;
            this.rows = rows;
            this.message = message;
            this.error = error;
            this.success = error == null;
            this.truncated = truncated;
        }

        public static PreviewResult failed(String errorCode, String errorMsg) {
            return new PreviewResult("UNKNOWN", 0, null, null,
                    null, "[" + errorCode + "] " + (errorMsg == null ? "" : errorMsg));
        }
    }
}
