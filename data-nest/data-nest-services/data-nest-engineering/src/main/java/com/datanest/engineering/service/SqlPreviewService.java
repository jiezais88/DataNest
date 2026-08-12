package com.datanest.engineering.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.dto.SqlPreviewResponse;
import com.datanest.engineering.dto.SqlPreviewResponse.StatementResult;
import com.datanest.task.core.service.DagParameterResolver;
import com.datanest.task.core.service.DorisSqlExecutor;
import com.datanest.task.core.service.GenericSqlExecutor;
import com.datanest.task.core.service.SqlStatementSplitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 与 GenericSqlExecutor.PREVIEW_MAX_ROWS 保持一致的预览行数上限 */
    private static final int PREVIEW_MAX_ROWS = 200;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GenericSqlExecutor genericSqlExecutor;
    private final DorisSqlExecutor dorisSqlExecutor;
    private final DagParameterResolver dagParameterResolver;

    public SqlPreviewService(GenericSqlExecutor genericSqlExecutor,
                             DorisSqlExecutor dorisSqlExecutor,
                             DagParameterResolver dagParameterResolver) {
        this.genericSqlExecutor = genericSqlExecutor;
        this.dorisSqlExecutor = dorisSqlExecutor;
        this.dagParameterResolver = dagParameterResolver;
    }

    public SqlPreviewResponse preview(String sql, Long datasourceId, Map<String, Object> params) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_PREVIEW_FAILED, "SQL must not be empty");
        }
        Map<String, Object> resolvedParams = resolveParams(params);
        SqlPreviewResponse resp = new SqlPreviewResponse();
        List<String> stmts = SqlStatementSplitter.split(sql);
        for (String rawStmt : stmts) {
            String stmt = dagParameterResolver.replacePlaceholders(rawStmt, resolvedParams);
            StatementResult r = executeOne(stmt, datasourceId);
            resp.getStatements().add(r);
        }
        return resp;
    }

    /**
     * Sprint 4：构造 SQL 预览用的参数 Map。
     * 系统变量（biz_date / current_time）始终可用，传入的草稿参数覆盖系统变量。
     */
    private Map<String, Object> resolveParams(Map<String, Object> draftParams) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        resolved.put("biz_date", now.minusDays(1).format(DATE_FMT));
        resolved.put("current_time", now.format(TIME_FMT));
        if (draftParams != null) {
            resolved.putAll(draftParams);
        }
        return resolved;
    }

    private StatementResult executeOne(String stmt, Long datasourceId) {
        StatementResult r = new StatementResult();
        r.setStmt(stmt);
        long startMs = System.currentTimeMillis();
        try {
            if (datasourceId == null) {
                String type = classify(stmt);
                if ("QUERY".equals(type)) {
                    // 查询语句要走 query() 拿结果集；execute() 只返回行数，结果集会被丢弃
                    DorisSqlExecutor.QueryResult qr = dorisSqlExecutor.query(stmt);
                    r.setStatus("SUCCESS");
                    r.setType(type);
                    r.setColumns(qr.columns());
                    // Map 行按列顺序转为位置数组，与 GenericSqlExecutor 的契约一致
                    List<List<Object>> rows = new java.util.ArrayList<>();
                    for (java.util.Map<String, Object> row : qr.rows()) {
                        List<Object> cells = new java.util.ArrayList<>(qr.columns().size());
                        for (String col : qr.columns()) {
                            cells.add(row.get(col));
                        }
                        rows.add(cells);
                    }
                    // 与 GenericSqlExecutor.PREVIEW_MAX_ROWS=200 保持同一截断口径
                    boolean truncated = qr.truncated() || rows.size() > PREVIEW_MAX_ROWS;
                    if (rows.size() > PREVIEW_MAX_ROWS) {
                        rows = new java.util.ArrayList<>(rows.subList(0, PREVIEW_MAX_ROWS));
                    }
                    r.setRows(rows);
                    r.setRowCount(rows.size());
                    r.setTruncated(truncated);
                    r.setMessage(rows.size() + " row(s)" + (truncated ? " (truncated)" : ""));
                } else {
                    int affected = dorisSqlExecutor.execute(stmt);
                    r.setStatus("SUCCESS");
                    r.setType(type);
                    r.setRowCount(affected);
                    r.setMessage(type.equals("DDL") ? "DDL executed" : "Affected " + affected + " row(s)");
                }
            } else {
                DataSourceInfo ds = genericSqlExecutor.getDatasource(datasourceId);
                GenericSqlExecutor.PreviewResult pr = genericSqlExecutor.execute(ds, stmt);
                r.setStatus(pr.success ? "SUCCESS" : "FAILED");
                r.setType(pr.type);
                r.setRowCount(pr.rowCount);
                r.setColumns(pr.columns);
                r.setRows(pr.rows);
                r.setMessage(pr.message);
                r.setError(pr.error);
                r.setTruncated(pr.truncated);
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
        } finally {
            r.setDurationMs(System.currentTimeMillis() - startMs);
        }
        return r;
    }

    private String classify(String sql) {
        return SqlStatementSplitter.classify(sql);
    }
}
