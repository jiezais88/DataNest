package com.datanest.dataservice.service;

import com.datanest.common.constant.DataSourceType;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.dataservice.dto.CustomSqlParamDef;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自定义 SQL 查询定义服务（Sprint 13，双形态之 CUSTOM_SQL）。
 * <p>
 * 职责（对齐技术文档 §3）：① 只读校验 + 涉及表解析（复用 {@link ReadOnlySqlValidator}，禁止重复实现）；
 * ② SQL 内 {@code :param} 命名占位符与 sqlParams 定义一一对应校验（9018）；
 * ③ 词法级 {@code :param} → {@code ?} 替换（排除单引号字符串与 -- / /* *&#47; 注释，参数名不进入 SQL 文本，杜绝注入）；
 * ④ 参数值按 sqlParams.type 类型强转（LONG/DECIMAL/DATE/DATETIME/STRING/BOOLEAN）；
 * ⑤ 分页/COUNT 外层包裹（按数据源方言生成）。
 */
@Service
public class CustomSqlService {

    /** 参数类型：整型 */
    public static final String TYPE_LONG = "LONG";
    /** 参数类型：小数 */
    public static final String TYPE_DECIMAL = "DECIMAL";
    /** 参数类型：日期 */
    public static final String TYPE_DATE = "DATE";
    /** 参数类型：日期时间 */
    public static final String TYPE_DATETIME = "DATETIME";
    /** 参数类型：字符串 */
    public static final String TYPE_STRING = "STRING";
    /** 参数类型：布尔 */
    public static final String TYPE_BOOLEAN = "BOOLEAN";

    private static final Set<String> PARAM_TYPES = Set.of(
            TYPE_LONG, TYPE_DECIMAL, TYPE_DATE, TYPE_DATETIME, TYPE_STRING, TYPE_BOOLEAN);

    private final ReadOnlySqlValidator readOnlySqlValidator;

    public CustomSqlService(ReadOnlySqlValidator readOnlySqlValidator) {
        this.readOnlySqlValidator = readOnlySqlValidator;
    }

    /**
     * 涉及表（已解析库/schema/表三元组）。
     *
     * @param database 库名（MySQL/Doris）；无限定时为数据源默认库
     * @param schema   schema 名（PG/Oracle/SQLServer）
     * @param table    表名
     */
    public record InvolvedTable(String database, String schema, String table) {

        /** 展示/血缘用限定名：schema.table（PG）或 database.table（MySQL/Doris）或 table */
        public String qualified() {
            if (schema != null && !schema.isBlank()) {
                return schema + "." + table;
            }
            if (database != null && !database.isBlank()) {
                return database + "." + table;
            }
            return table;
        }
    }

    /** 构造结果：参数化 SQL（? 占位）+ 参数值（与占位一一对应） */
    public record BuiltSql(String sql, List<Object> params) {
    }

    /**
     * 只读校验 + 涉及表解析（复用 ReadOnlySqlValidator，非只读/语法错误抛 9001/9002）。
     *
     * @param sql          自定义 SQL 文本
     * @param databaseName 数据源默认库名（未限定表名的归属库）
     * @param schemaName   数据源默认 schema（PG 系）
     * @return 解析后的涉及表清单（去重保序）；空表示无表引用
     */
    public List<InvolvedTable> extractInvolvedTables(String sql, String databaseName, String schemaName) {
        List<String> rawTables = readOnlySqlValidator.validateAndExtractTables(sql);
        List<InvolvedTable> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : rawTables) {
            String normalized = normalize(raw);
            String[] parts = normalized.split("\\.", -1);
            String database = databaseName;
            String schema = schemaName;
            String table;
            if (parts.length >= 3) {
                // catalog.schema.table（罕见，PG/三段落限定）
                database = parts[parts.length - 3];
                schema = parts[parts.length - 2];
                table = parts[parts.length - 1];
            } else if (parts.length == 2) {
                // db.table / schema.table：限定符命中默认库/schema 时归并，否则按跨库引用保留限定库
                if (equalsIgnoreCase(parts[0], databaseName)) {
                    table = parts[1];
                } else if (schemaName != null && equalsIgnoreCase(parts[0], schemaName)) {
                    schema = schemaName;
                    table = parts[1];
                } else {
                    database = parts[0];
                    schema = null;
                    table = parts[1];
                }
            } else {
                table = parts[0];
            }
            if (table == null || table.isBlank()) {
                continue;
            }
            String key = (database == null ? "" : database) + "|" + (schema == null ? "" : schema) + "|" + table;
            if (seen.add(key)) {
                result.add(new InvolvedTable(trimToNull(database), trimToNull(schema), table.trim()));
            }
        }
        return result;
    }

    /**
     * 参数定义校验（9018 CUSTOM_SQL_PARAM_MISMATCH）：SQL 中 {@code :param} 占位符与 sqlParams 定义一一对应
     * （多定义/漏定义/参数类型非法均拒绝），并返回 SQL 内出现的参数名（保序去重）。
     */
    public List<String> validateParamDefs(String sql, List<CustomSqlParamDef> sqlParams) {
        List<String> placeholders = scanParams(sql).params();
        Set<String> defined = new LinkedHashSet<>();
        if (sqlParams != null) {
            for (CustomSqlParamDef def : sqlParams) {
                String name = def.getName() == null ? "" : def.getName().trim();
                if (name.isEmpty()) {
                    throw new BusinessException(ErrorCode.CUSTOM_SQL_PARAM_MISMATCH, "参数定义存在空参数名");
                }
                String type = def.getType() == null ? "" : def.getType().trim().toUpperCase();
                if (!PARAM_TYPES.contains(type)) {
                    throw new BusinessException(ErrorCode.CUSTOM_SQL_PARAM_MISMATCH,
                            "参数类型仅支持 LONG/DECIMAL/DATE/DATETIME/STRING/BOOLEAN: " + name + "=" + def.getType());
                }
                defined.add(name);
            }
        }
        List<String> missing = placeholders.stream().filter(p -> !defined.contains(p)).toList();
        List<String> extra = defined.stream().filter(d -> !placeholders.contains(d)).toList();
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new BusinessException(ErrorCode.CUSTOM_SQL_PARAM_MISMATCH,
                    "SQL 参数与定义不一致：SQL 中存在未定义参数 " + missing + "，定义中多余参数 " + extra);
        }
        return placeholders;
    }

    /**
     * 构造参数化执行 SQL：词法级 {@code :param} → {@code ?}，参数值按定义类型强转并绑定。
     * <p>
     * 执行前再次校验参数定义（防 SQL 落库后被编辑绕过，fail-closed 兜底，技术文档 §3.3）。
     * 必填参数缺省 → 9018；选填参数缺省时取 defaultValue，无默认值则绑定 NULL（调用方需保证 SQL 条件语义）。
     */
    public BuiltSql buildQuery(String sql, List<CustomSqlParamDef> sqlParams, Map<String, String> queryParams) {
        List<String> placeholders = validateParamDefs(sql, sqlParams);
        ScanResult scan = scanParams(sql);
        Map<String, CustomSqlParamDef> defMap = new LinkedHashMap<>();
        if (sqlParams != null) {
            for (CustomSqlParamDef def : sqlParams) {
                defMap.put(def.getName().trim(), def);
            }
        }
        List<Object> values = new ArrayList<>(placeholders.size());
        for (String name : placeholders) {
            CustomSqlParamDef def = defMap.get(name);
            String raw = queryParams == null ? null : queryParams.get(name);
            if (raw == null || raw.isBlank()) {
                if (def.getRequired() == null || def.getRequired()) {
                    throw new BusinessException(ErrorCode.CUSTOM_SQL_PARAM_MISMATCH, "缺少必填参数: " + name);
                }
                String defaultValue = def.getDefaultValue();
                if (defaultValue == null || defaultValue.isBlank()) {
                    values.add(null);
                    continue;
                }
                raw = defaultValue;
            }
            values.add(convertValue(raw, def.getType(), name));
        }
        return new BuiltSql(scan.sql(), values);
    }

    /**
     * 分页包裹：{@code SELECT * FROM (sql) AS _p + 分页}，方言与 OpenApiSqlBuilder.buildPagination 对齐
     * （Doris/MySQL 用 LIMIT offset, size；PG 用 LIMIT size OFFSET offset；Oracle/SQLServer 用 OFFSET FETCH）。
     * 内层 SQL 尾部多余分号先剥离（防外层包裹语法错误）。
     */
    public String wrapPagination(String baseSql, String type, int page, int pageSize) {
        int offset = Math.max(page - 1, 0) * pageSize;
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new BusinessException(ErrorCode.API_DEFINITION_INVALID, "不支持的数据源类型: " + type);
        }
        String paging = switch (dataSourceType) {
            case MYSQL, DORIS -> " LIMIT " + offset + ", " + pageSize;
            case POSTGRESQL -> " LIMIT " + pageSize + " OFFSET " + offset;
            case ORACLE, SQLSERVER -> " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
        };
        return "SELECT * FROM (" + stripTrailingSemicolon(baseSql) + ") AS _p" + paging;
    }

    /** COUNT 包裹：{@code SELECT COUNT(*) FROM (sql) AS _c} */
    public String wrapCount(String baseSql) {
        return "SELECT COUNT(*) FROM (" + stripTrailingSemicolon(baseSql) + ") AS _c";
    }

    // ---------- 内部方法 ----------

    /** 词法级 :param 扫描结果：替换后的 SQL + 参数名列表（保序去重） */
    private record ScanResult(String sql, List<String> params) {
    }

    /**
     * 词法级扫描：单引号字符串、-- 行注释、/* 块注释 *&#47; 内的内容原样保留不替换；
     * NORMAL 态命中 {@code :[a-zA-Z_][a-zA-Z0-9_]*} 替换为 {@code ?}（参数名不进入 SQL）；
     * PG 强转 {@code ::} 原样保留（避免把 {@code ::date} 误判为参数 {@code :date}）。
     */
    private ScanResult scanParams(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> params = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // 单引号字符串：支持 '' 转义与反斜杠转义
                int start = i;
                i++;
                while (i < n) {
                    char s = sql.charAt(i);
                    if (s == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    if (s == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    i++;
                }
                out.append(sql, start, i);
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                // -- 行注释
                int start = i;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(sql, start, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                // /* 块注释 */
                int start = i;
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = i + 1 < n ? i + 2 : n;
                out.append(sql, start, i);
                continue;
            }
            if (c == ':') {
                if (i + 1 < n && sql.charAt(i + 1) == ':') {
                    out.append("::");
                    i += 2;
                    continue;
                }
                int j = i + 1;
                if (j < n && isIdentStart(sql.charAt(j))) {
                    int end = j + 1;
                    while (end < n && isIdentPart(sql.charAt(end))) {
                        end++;
                    }
                    String name = sql.substring(j, end);
                    if (seen.add(name)) {
                        params.add(name);
                    }
                    out.append('?');
                    i = end;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return new ScanResult(out.toString(), params);
    }

    /** 参数值类型强转（LONG/DECIMAL/DATE/DATETIME/STRING/BOOLEAN），失败 → 9018 参数与定义不符 */
    private Object convertValue(String raw, String type, String name) {
        String value = raw.trim();
        String t = type == null ? "" : type.trim().toUpperCase();
        try {
            return switch (t) {
                case TYPE_LONG -> Long.parseLong(value);
                case TYPE_DECIMAL -> new BigDecimal(value);
                case TYPE_DATE -> parseDate(value);
                case TYPE_DATETIME -> parseDateTime(value);
                case TYPE_BOOLEAN -> Boolean.parseBoolean(value);
                case TYPE_STRING -> value;
                default -> throw new BusinessException(ErrorCode.CUSTOM_SQL_PARAM_MISMATCH,
                        "参数类型非法: " + name + "=" + type);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.CUSTOM_SQL_PARAM_MISMATCH,
                    "参数值与类型不符: " + name + "（期望 " + t + "，实际 " + raw + "）");
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            // 兼容带时间的值与空格分隔
            return LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate();
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return LocalDateTime.parse(value.replace(' ', 'T'));
        }
    }

    /** 归一化表引用：剥离反引号/双引号/方括号（含限定符整体） */
    private String normalize(String raw) {
        String t = raw.trim();
        if (t.length() > 1 && ((t.startsWith("`") && t.endsWith("`"))
                || (t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("[") && t.endsWith("]")))) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
    }

    private String stripTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
