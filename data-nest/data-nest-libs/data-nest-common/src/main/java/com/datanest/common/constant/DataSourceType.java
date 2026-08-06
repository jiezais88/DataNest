package com.datanest.common.constant;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据源类型枚举。
 */
public enum DataSourceType {

    MYSQL("MYSQL", "MySQL"),
    POSTGRESQL("POSTGRESQL", "PostgreSQL"),
    DORIS("DORIS", "Doris"),
    ORACLE("ORACLE", "Oracle"),
    SQLSERVER("SQLSERVER", "SQL Server");

    /**
     * 用于 Bean Validation 注解的编译期常量正则。
     * 新增类型时必须同步更新此常量。
     */
    public static final String PATTERN = "^(MYSQL|POSTGRESQL|DORIS|ORACLE|SQLSERVER)$";
    public static final String ALLOWED_LABELS = "MYSQL、POSTGRESQL、DORIS、ORACLE、SQLSERVER";

    private final String code;
    private final String label;

    DataSourceType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 根据 code 查找枚举，找不到返回 null。
     */
    public static DataSourceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (DataSourceType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 判断 code 是否为合法的数据源类型。
     */
    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }

    /**
     * 返回所有 code，用于正则拼接。
     */
    public static List<String> codes() {
        return Arrays.stream(values()).map(DataSourceType::getCode).collect(Collectors.toList());
    }

    /**
     * 返回用于正则的 code 联合字符串，如 MYSQL|POSTGRESQL|DORIS|ORACLE|SQLSERVER。
     */
    public static String regexPattern() {
        return codes().stream().collect(Collectors.joining("|"));
    }

    /**
     * 是否需要 database/schema 双层语义（即 database 下还要展开 schema）。
     * MySQL / Doris 的 database 与 schema 等价，直接挂表。
     */
    public boolean hasSchemaLayer() {
        return this == POSTGRESQL || this == ORACLE || this == SQLSERVER;
    }

    /**
     * 是否与 MySQL 协议兼容（Doris 复用 MySQL JDBC 驱动与语法）。
     */
    public boolean isMySQLCompatible() {
        return this == MYSQL || this == DORIS;
    }

    public boolean is(String code) {
        return Objects.equals(this.code, code);
    }
}
