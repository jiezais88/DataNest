package com.datanest.common.audit;

/**
 * 审计内容拼装工具（供 @AuditLog 的 content SpEL 表达式静态调用）。
 * <p>
 * SQL 查询审计内容规范（PRD §6.1.2）：SQL 摘要（前 200 字符）+ 返回行数 + 耗时。
 */
public final class AuditContent {

    private static final int SQL_MAX = 4000;

    private AuditContent() {
    }

    /** SQL 查询内容：完整 SQL（列表页前端 ellipsis 截断、详情抽屉完整展示）+ 行数 + 耗时；不含查询结果数据（B2） */
    public static String sql(String sql, Integer rowCount, Integer durationMs) {
        String s = sql == null ? "" : sql.trim();
        if (s.length() > SQL_MAX) {
            s = s.substring(0, SQL_MAX);
        }
        return s
                + " | 行数:" + (rowCount == null ? "-" : rowCount)
                + " | 耗时:" + (durationMs == null ? "-" : durationMs) + "ms";
    }
}
