package com.datanest.engineering.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.dto.SqlPreviewResponse;
import com.datanest.engineering.dto.SqlPreviewResponse.StatementResult;
import com.datanest.task.core.entity.DataSourceConnection;
import com.datanest.task.core.service.DorisSqlExecutor;
import com.datanest.task.core.service.SqlStatementSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SQL preview service: split -> execute -> wrap response.
 * <p>
 * Splitting: by ';' but respects string literals ('..'/"..") and SQL comments.
 * Empty / pure-comment statements are skipped.
 * <p>
 * Behavior:
 * - datasourceId provided -> query DataSource, GenericSqlExecutor dispatches
 *   to the right JDBC driver
 * - datasourceId null -> use built-in Doris (DorisSqlExecutor), same as formal
 *   DS callback path
 * - one statement failure does not block others: each statement is wrapped in
 *   its own try/catch, failure recorded as status=FAILED + error
 */
@Service
public class SqlPreviewService {

    private static final Logger logger = LoggerFactory.getLogger(SqlPreviewService.class);

    private final GenericSqlExecutor genericSqlExecutor;
    private final DorisSqlExecutor dorisSqlExecutor;

    public SqlPreviewService(GenericSqlExecutor genericSqlExecutor, DorisSqlExecutor dorisSqlExecutor) {
        this.genericSqlExecutor = genericSqlExecutor;
        this.dorisSqlExecutor = dorisSqlExecutor;
    }

    public SqlPreviewResponse preview(String sql, Long datasourceId) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_PREVIEW_FAILED, "SQL must not be empty");
        }
        SqlPreviewResponse resp = new SqlPreviewResponse();
        List<String> stmts = SqlStatementSplitter.split(sql);
        for (String stmt : stmts) {
            StatementResult r = executeOne(stmt, datasourceId);
            resp.getStatements().add(r);
        }
        return resp;
    }

    private StatementResult executeOne(String stmt, Long datasourceId) {
        StatementResult r = new StatementResult();
        r.setStmt(stmt);
        try {
            if (datasourceId == null) {
                int affected = dorisSqlExecutor.execute(stmt);
                String type = classify(stmt);
                r.setStatus("SUCCESS");
                r.setType(type);
                r.setRowCount(affected);
                r.setMessage(type.equals("DDL") ? "DDL executed" : "Affected " + affected + " row(s)");
            } else {
                DataSourceConnection ds = genericSqlExecutor.getDataSource(datasourceId);
                GenericSqlExecutor.PreviewResult pr = genericSqlExecutor.execute(ds, stmt);
                r.setStatus(pr.success ? "SUCCESS" : "FAILED");
                r.setType(pr.type);
                r.setRowCount(pr.rowCount);
                r.setColumns(pr.columns);
                r.setRows(pr.rows);
                r.setMessage(pr.message);
                r.setError(pr.error);
            }
        } catch (BusinessException e) {
            r.setStatus("FAILED");
            r.setType(classify(stmt));
            r.setError("[" + e.getErrorCode().getCode() + "] " + e.getMessage());
        } catch (Exception e) {
            logger.error("SQL preview statement exception: stmt={}", stmt, e);
            r.setStatus("FAILED");
            r.setType("UNKNOWN");
            r.setError("[SQL_PREVIEW_FAILED] " + e.getMessage());
        }
        return r;
    }

    private String classify(String sql) {
        String trimmed = sql.trim();
        int firstSpace = trimmed.indexOf(' ');
        String first = firstSpace > 0 ? trimmed.substring(0, firstSpace) : trimmed;
        String upper = first.toUpperCase();
        if (upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("SHOW")
                || upper.startsWith("DESC") || upper.startsWith("EXPLAIN") || upper.startsWith("VALUES")) {
            return "QUERY";
        }
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
}
