package com.datanest.dataservice.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 只读校验（Sprint 10 F1，JSqlParser 语法级，用户 q-0 已确认对齐技术文档 §4.1）。
 * <p>
 * 策略：多语句逐条 JSqlParser 解析；仅放行只读语句（SELECT / UNION 等 SetOperation /
 * SHOW / DESC / EXPLAIN / SET 会话级只读语句），DML/DDL/其它一律拦截（SQL_NOT_READ_ONLY）；
 * 解析失败抛 SQL_SYNTAX_ERROR（含注释/子查询绕过用例，AC-1）。
 * 同时用 TablesNamesFinder 提取引用的表集合（供敏感度校验）。
 */
@Service
public class ReadOnlySqlValidator {

    /** 放行语句类型集合（只读集，对齐技术文档 §4.1：SELECT/WITH/SHOW/DESC/EXPLAIN） */
    private static final Set<Class<?>> READ_ONLY_TYPES = Set.of(
            Select.class, SetOperationList.class,
            ShowStatement.class, DescribeStatement.class,
            ExplainStatement.class
    );

    /**
     * 校验并返回引用的表集合（如 db.table / schema.table / table）。
     *
     * @return 去重后的表名列表（空表示无表引用，如 SHOW 语句）
     * @throws BusinessException SQL_SYNTAX_ERROR（语法错）或 SQL_NOT_READ_ONLY（非只读）
     */
    public List<String> validateAndExtractTables(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_SYNTAX_ERROR, "SQL 不能为空");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new BusinessException(ErrorCode.SQL_SYNTAX_ERROR, "SQL 语法错误: " + e.getMessage());
        }
        if (statements == null || statements.getStatements().isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_SYNTAX_ERROR, "SQL 不能为空");
        }

        Set<String> tables = new LinkedHashSet<>();
        TablesNamesFinder finder = new TablesNamesFinder();
        for (Statement statement : statements.getStatements()) {
            if (!isReadOnly(statement)) {
                throw new BusinessException(ErrorCode.SQL_NOT_READ_ONLY,
                        "仅允许 SELECT/WITH/SHOW/DESC/EXPLAIN 只读语句，禁止执行: " + statement.getClass().getSimpleName());
            }
            // 提取表引用（Select 含子查询/UNION；SHOW/EXPLAIN 通常无表）
            List<String> stmtTables = finder.getTableList(statement);
            if (stmtTables != null) {
                tables.addAll(stmtTables);
            }
        }
        return new ArrayList<>(tables);
    }

    private boolean isReadOnly(Statement statement) {
        for (Class<?> type : READ_ONLY_TYPES) {
            if (type.isAssignableFrom(statement.getClass())) {
                return true;
            }
        }
        // 显式拦截（可读性：其余全部拒绝）
        if (statement instanceof Insert || statement instanceof Update || statement instanceof Delete
                || statement instanceof Merge || statement instanceof Truncate
                || statement instanceof CreateTable || statement instanceof Alter || statement instanceof Drop) {
            return false;
        }
        return false;
    }
}
