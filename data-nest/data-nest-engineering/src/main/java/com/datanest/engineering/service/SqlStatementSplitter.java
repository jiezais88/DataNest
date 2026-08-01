package com.datanest.engineering.service;

import org.apache.ibatis.jdbc.SQL;

import java.util.ArrayList;
import java.util.List;

/**
 * 简易 SQL statement 切分器。
 * <p>
 * 规则：
 * - 按 ; 切分（多 statement 用分号分隔是 SQL 通用约定）
 * - 跳过字符串字面量（'…' / "…"）内的分号，识别 '' / "" 转义
 * - 跳过单行注释（-- …）和多行注释（/* … */）
        *-跳过空 /
纯注释 statement
 * <p>
 *边界：
未处理 PL/
SQL 块（DECLARE…BEGIN…END）、
未处理 $$
美元引号（PostgreSQL）。
        *
这些场景超出 Sprint 3
preview 需求；
DAG 节点
SQL 通常是单条
DDL/DML/SELECT。
        * <p>
 *
决策 ADR-S3-FJ-005：自己写切分器，
避免引入 jsqlparser
依赖；
        *
preview 场景对边界容忍度高（失败回退到单条执行），
不需要工业级 parser。
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
        char inString = 0; // 0 = 不在字符串内；' 或 "
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
                // 行注释内的字符（包括 ;）原样保留但不算 statement 边界
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
                    // 反斜杠转义（MySQL 等支持）：保留下一个字符
                    cur.append(next);
                    i += 2;
                    continue;
                }
                if (c == inString) {
                    // 字符串结束；额外判断 '' / ""（SQL 标准双引号转义）
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

            // 不在字符串 / 注释中
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
                // statement 边界
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

        // 收尾
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }
}
