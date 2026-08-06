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
