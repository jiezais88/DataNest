package com.datanest.task.core.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple SQL statement splitter.
 * <p>
 * Rules:
 * - Split by ';' (multi-statement scripts use semicolons by SQL convention)
 * - Skip semicolons inside string literals ('..' / ".."), with '' / "" escape support
 * - Skip single-line (--) and multi-line (slash-star) comments
 * - Skip empty / pure-comment statements
 * <p>
 * Limitations: does not handle PL/SQL blocks (DECLARE..BEGIN..END) or
 * PostgreSQL $$ dollar-quoting. These are out of scope for Sprint 3 preview
 * (DAG node SQL is usually single DDL/DML/SELECT).
 * <p>
 * Decision ADR-S3-FJ-005: write our own splitter instead of pulling in
 * jsqlparser; preview has tolerant failure semantics (fallback to single
 * execution on parse miss) so we don't need an industrial-strength parser.
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    /**
     * SQL 语句类型分类（只取首词判断，SimpleEvaluationContext 同类朴素实现）。
     * <p>
     * 返回四分类：QUERY / DDL / DML / UNKNOWN。
     * 2026-08-12 收敛来源：worker DagNodeExecuteService.classifySql 与
     * engineering SqlPreviewService.classify 逐字相同，GenericSqlExecutor.classifyDmlDdl
     * 是其子集（DDL/DML/UNKNOWN），统一委托到此处，避免三处重复维护。
     */
    public static String classify(String sql) {
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

    public static List<String> split(String sql) {
        List<String> result = new ArrayList<>();
        if (sql == null) {
            return result;
        }

        StringBuilder cur = new StringBuilder();
        int n = sql.length();
        int i = 0;
        char inString = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        while (i < n) {
            char c = sql.charAt(i);
            char next = (i + 1 < n) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    cur.append(c);
                }
                i++;
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    cur.append(c).append(next);
                    i += 2;
                    continue;
                }
                cur.append(c);
                i++;
                continue;
            }

            if (inString != 0) {
                cur.append(c);
                if (c == '\\' && next != '\0') {
                    cur.append(next);
                    i += 2;
                    continue;
                }
                if (c == inString) {
                    if (next == inString) {
                        cur.append(next);
                        i += 2;
                        continue;
                    }
                    inString = 0;
                }
                i++;
                continue;
            }

            if (c == '\'' || c == '"') {
                inString = c;
                cur.append(c);
                i++;
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                cur.append(c).append(next);
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                cur.append(c).append(next);
                i += 2;
                continue;
            }
            if (c == ';') {
                String stmt = cur.toString().trim();
                if (!stmt.isEmpty()) {
                    result.add(stmt);
                }
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }

        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }
}
