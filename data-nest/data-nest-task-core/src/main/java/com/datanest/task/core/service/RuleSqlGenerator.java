package com.datanest.task.core.service;

import com.datanest.task.core.entity.MetadataTable;
import com.datanest.task.core.entity.QualityRuleTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 质量规则执行 SQL 生成器（Sprint 6 配置层）。
 * <p>
 * 规则 {@code sql_expression} 执行时动态生成：把模板 SQL 中的占位符
 * {@code {table}} / {@code {column}} / {@code {min}} / {@code {max}} 替换为具体值。
 * 本次提供生成函数供预览，下一批执行校验直接复用。
 * <p>
 * 占位符约定（对齐内置模板种子数据）：
 * <ul>
 *   <li>{@code {table}}  → 完整表名（schema.table，schema 为空则仅 table）</li>
 *   <li>{@code {column}} → 检查字段名</li>
 *   <li>{@code {min}}    → 值域下限（RANGE）</li>
 *   <li>{@code {max}}    → 值域上限（RANGE）</li>
 * </ul>
 */
public final class RuleSqlGenerator {

    private RuleSqlGenerator() {
    }

    /**
     * 生成最终执行 SQL。
     *
     * @param template   来源模板（其 sql_template 含占位符；CUSTOM_SQL 模板 sql_template 为 null）
     * @param table      目标元数据表（用于拼 {@code {table}} 完整表名）
     * @param columnName 检查字段（可空）
     * @param min        值域下限（可空）
     * @param max        值域上限（可空）
     * @param customSql  自定义 SQL（模板为 CUSTOM_SQL 时使用，可为 null）
     * @return 替换占位符后的最终 SQL；模板 SQL 为空且无自定义 SQL 时返回 null
     */
    public static String generate(QualityRuleTemplate template, MetadataTable table,
                                  String columnName, BigDecimal min, BigDecimal max, String customSql) {
        if (template == null) {
            return customSql;
        }
        if (isCustomSql(template)) {
            return customSql;
        }
        String sqlTemplate = template.getSqlTemplate();
        if (!StringUtils.hasText(sqlTemplate)) {
            return customSql;
        }
        String result = sqlTemplate;
        result = result.replace("{table}", buildFullTableName(table));
        result = result.replace("{column}", columnName == null ? "" : columnName);
        result = result.replace("{min}", min == null ? "" : min.stripTrailingZeros().toPlainString());
        result = result.replace("{max}", max == null ? "" : max.stripTrailingZeros().toPlainString());
        return result;
    }

    /**
     * 判断模板是否为自定义 SQL 类型。
     */
    public static boolean isCustomSql(QualityRuleTemplate template) {
        return template != null && "CUSTOM_SQL".equalsIgnoreCase(template.getType());
    }

    /**
     * 拼接完整表名：schema.table（schema 为空则仅 table）。对齐项目 schemaName + "." + tableName 范式。
     */
    public static String buildFullTableName(MetadataTable table) {
        if (table == null) {
            return "";
        }
        if (StringUtils.hasText(table.getSchemaName())) {
            return table.getSchemaName() + "." + table.getTableName();
        }
        return table.getTableName();
    }
}
