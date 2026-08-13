package com.datanest.dataservice.service;

import com.datanest.common.constant.DataSourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.dataservice.dto.ApiParamDef;
import com.datanest.dataservice.dto.DataApiDefinition;
import com.datanest.dataservice.entity.DataApi;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对外数据 API 的只读 SELECT 语句构造器（Sprint 10 F3）。
 * <p>
 * 由已白名单校验的 API 定义（filters EQ/RANGE + fields 返回字段）拼装参数化 SQL
 * （{@code WHERE field = ?}），参数值从请求 query 参数取值，按值启发式推断绑定类型
 * （整数 → Long / 小数 → BigDecimal / 其余 → String），避免 PG 数值列 setString 类型不匹配。
 * <p>
 * 表名/列名按数据源类型转义（防注入），排序仅接受定义时已白名单校验的「列名 ASC|DESC」，
 * 分页按数据源类型生成 LIMIT/OFFSET 或 OFFSET FETCH 语法。
 */
@Component
public class OpenApiSqlBuilder {

    private static final Pattern INT_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^-?\\d+\\.\\d+$");

    /** 拼接结果：SQL（参数占位 ?）+ 参数值（与占位一一对应） */
    public record BuiltSql(String sql, List<Object> params) {
    }

    /**
     * 拼装只读 SELECT 语句。
     *
     * @param api         API 定义（表名/排序/分页开关/库/schema）
     * @param definition  filters + fields 定义（F2 已白名单校验）
     * @param type        数据源类型（内置 Doris 为 DORIS）
     * @param queryParams 请求 query 参数（EQ 用 field，RANGE 用 min_field / max_field）
     * @param page        页码（从 1 开始）
     * @param pageSize    每页条数（已 clamp 到 [1, pageSizeMax]）
     */
    public BuiltSql build(DataApi api, DataApiDefinition definition, String type,
                          Map<String, String> queryParams, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(buildSelectColumns(definition, type));
        sql.append(" FROM ").append(buildQualifiedTable(type, api.getDatabaseName(),
                api.getSchemaName(), api.getTableName()));

        List<Object> params = new ArrayList<>();
        buildWhere(definition, queryParams, type, sql, params);

        if (api.getOrderBy() != null && !api.getOrderBy().isBlank()) {
            sql.append(" ORDER BY ").append(buildOrderBy(type, api.getOrderBy()));
        }
        if (api.getPaginated() != null && api.getPaginated() == 1) {
            sql.append(buildPagination(type, page, pageSize));
        }
        return new BuiltSql(sql.toString(), params);
    }

    /**
     * 拼装 COUNT 语句（分页时取总记录数）：WHERE 条件与 {@link #build} 完全一致。
     */
    public BuiltSql buildCount(DataApi api, DataApiDefinition definition, String type,
                               Map<String, String> queryParams) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(buildQualifiedTable(type, api.getDatabaseName(), api.getSchemaName(), api.getTableName()));
        List<Object> params = new ArrayList<>();
        buildWhere(definition, queryParams, type, sql, params);
        return new BuiltSql(sql.toString(), params);
    }

    /** 返回字段列表：空 = 全部（*），否则按定义转义拼接 */
    private String buildSelectColumns(DataApiDefinition definition, String type) {
        List<String> fields = definition.getFields();
        if (fields == null || fields.isEmpty()) {
            return "*";
        }
        // 列标识符已在 F2 白名单校验（IDENTIFIER_PATTERN），按数据源类型转义防注入
        return fields.stream().map(f -> quoteIdentifier(type, f)).reduce((a, b) -> a + ", " + b).orElse("*");
    }

    /** WHERE 子句：filters 组合为 AND，参数占位 ? 并收集参数值 */
    private void buildWhere(DataApiDefinition definition, Map<String, String> queryParams,
                            String type, StringBuilder sql, List<Object> params) {
        List<ApiParamDef> filters = definition.getFilters();
        if (filters == null || filters.isEmpty()) {
            return;
        }
        List<String> conditions = new ArrayList<>();
        for (ApiParamDef filter : filters) {
            String column = quoteIdentifier(type, filter.getField());
            if (ApiParamDef.TYPE_RANGE.equals(filter.getType())) {
                String min = queryParams.get("min_" + filter.getField());
                String max = queryParams.get("max_" + filter.getField());
                // 范围筛选：上下限均为空时跳过该条件（不强制必填）
                if (min != null && !min.isBlank()) {
                    conditions.add(column + " >= ?");
                    params.add(inferValue(min));
                }
                if (max != null && !max.isBlank()) {
                    conditions.add(column + " <= ?");
                    params.add(inferValue(max));
                }
            } else {
                String value = queryParams.get(filter.getField());
                if (value == null || value.isBlank()) {
                    continue; // 等值筛选未传值则跳过（PRD：可选参数）
                }
                conditions.add(column + " = ?");
                params.add(inferValue(value));
            }
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    /** 排序：定义已白名单校验为「列名 ASC|DESC」，列名转义、方向保留 */
    private String buildOrderBy(String type, String orderBy) {
        String ob = orderBy.trim();
        int space = ob.indexOf(' ');
        if (space < 0) {
            return quoteIdentifier(type, ob);
        }
        String column = ob.substring(0, space);
        String direction = ob.substring(space + 1).trim().toUpperCase();
        return quoteIdentifier(type, column) + " " + direction;
    }

    /** 分页：按数据源类型生成对应语法（page 从 1 开始） */
    private String buildPagination(String type, int page, int pageSize) {
        int offset = Math.max(page - 1, 0) * pageSize;
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "不支持的数据源类型: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL, DORIS -> " LIMIT " + offset + ", " + pageSize;
            case POSTGRESQL -> " LIMIT " + pageSize + " OFFSET " + offset;
            case ORACLE, SQLSERVER -> " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
        };
    }

    /** 表名引用：按数据源类型拼接 库/schema/表 并转义 */
    private String buildQualifiedTable(String type, String database, String schema, String tableName) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "不支持的数据源类型: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL, DORIS -> hasText(database) ? quoteIdentifier(type, database) + "." + quoteIdentifier(type, tableName)
                    : quoteIdentifier(type, tableName);
            case POSTGRESQL, ORACLE, SQLSERVER -> hasText(schema) ? quoteIdentifier(type, schema) + "." + quoteIdentifier(type, tableName)
                    : quoteIdentifier(type, tableName);
        };
    }

    /** 标识符转义（表名/列名防注入，与 common JdbcPreviewHelper 一致的分支） */
    private String quoteIdentifier(String type, String name) {
        DataSourceType dataSourceType = type == null ? DataSourceType.MYSQL : DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            dataSourceType = DataSourceType.MYSQL;
        }
        return switch (dataSourceType) {
            case POSTGRESQL, ORACLE -> "\"" + name.replace("\"", "\"\"") + "\"";
            case MYSQL, DORIS -> "`" + name.replace("`", "``") + "`";
            case SQLSERVER -> "[" + name.replace("]", "]]") + "]";
        };
    }

    /** 参数值类型启发式推断：整数 → Long，小数 → BigDecimal，其余 → String */
    private Object inferValue(String raw) {
        String value = raw.trim();
        if (INT_PATTERN.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // 超出 long 范围 → 退回字符串
            }
        }
        if (DECIMAL_PATTERN.matcher(value).matches()) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException ignored) {
                // 非法小数 → 退回字符串
            }
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
