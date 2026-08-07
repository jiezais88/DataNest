package com.datanest.task.core.service;

import com.datanest.common.config.EncryptionConfig;
import com.datanest.common.constant.DataSourceType;
import com.datanest.common.util.JdbcUrlBuilder;
import com.datanest.engineering.api.dto.DataSourceInfo;
import com.datanest.engineering.api.dto.SyncJobInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 增量同步字段类型识别与最大值格式化工具。
 * <p>
 * 根据源端和目标端字段的实际数据类型，生成类型安全的增量 WHERE 条件比较值。
 */
@Component
public class IncrementalFieldTypeResolver {

    private static final Logger logger = LoggerFactory.getLogger(IncrementalFieldTypeResolver.class);

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Value("${datanest.doris.fe-host:localhost}")
    private String dorisFeHost;

    @Value("${datanest.doris.fe-query-port:9030}")
    private int dorisFeQueryPort;

    @Value("${datanest.doris.user:root}")
    private String dorisQueryUser;

    @Value("${datanest.doris.password:}")
    private String dorisQueryPassword;

    private final EncryptionConfig encryptionConfig;

    public IncrementalFieldTypeResolver(EncryptionConfig encryptionConfig) {
        this.encryptionConfig = encryptionConfig;
    }

    /**
     * 字段类型分类。
     */
    public enum TypeCategory {
        NUMERIC,
        TEMPORAL,
        OTHER
    }

    /**
     * 查询源端增量字段的类型分类。
     *
     * @param source      源数据源连接
     * @param job         同步任务（提供 sourceDatabase / sourceSchema）
     * @param sourceTable 源表名
     * @param fieldName   增量字段名
     * @return 类型分类，查询失败返回 OTHER
     */
    public TypeCategory resolveSourceFieldType(DataSourceInfo source, SyncJobInfo job,
                                               String sourceTable, String fieldName) {
        String jdbcUrl = JdbcUrlBuilder.buildJdbcUrl(source.getType(), source.getHost(), source.getPort(),
                source.getDatabaseName(), source.getSchemaName());
        String password = encryptionConfig.decrypt(source.getEncryptedPassword());
        String sourceDb = job.getSourceDatabase() != null && !job.getSourceDatabase().isBlank()
                ? job.getSourceDatabase() : source.getDatabaseName();
        String sourceSchema = job.getSourceSchema() != null && !job.getSourceSchema().isBlank()
                ? job.getSourceSchema() : source.getSchemaName();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, source.getUsername(), password)) {
            String typeName = queryColumnTypeName(connection, source.getType(), sourceDb,
                    sourceSchema, sourceTable, fieldName);
            if (typeName != null) {
                return classifyType(typeName);
            }
        } catch (Exception e) {
            logger.warn("查询源端增量字段类型失败: syncJobTable={}, field={}, error={}",
                    sourceTable, fieldName, e.getMessage());
        }
        return TypeCategory.OTHER;
    }

    /**
     * 查询 Doris 目标端增量字段的类型分类。
     *
     * @param targetDb     目标 Doris 库名
     * @param targetTable  目标表名
     * @param fieldName    增量字段名
     * @return 类型分类，查询失败返回 OTHER
     */
    public TypeCategory resolveTargetFieldType(String targetDb, String targetTable, String fieldName) {
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                dorisFeHost, dorisFeQueryPort, targetDb);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dorisQueryUser, dorisQueryPassword)) {
            String typeName = queryColumnTypeName(connection, DataSourceType.DORIS.getCode(), targetDb, null, targetTable, fieldName);
            if (typeName != null) {
                return classifyType(typeName);
            }
        } catch (Exception e) {
            logger.warn("查询目标端增量字段类型失败: targetTable={}, field={}, error={}",
                    targetTable, fieldName, e.getMessage());
        }
        return TypeCategory.OTHER;
    }

    /**
     * 判断源端和目标端字段类型是否可安全用于增量比较。
     * <p>
     * 要求两端同属数值型或同属时间型，避免 int vs varchar 等不一致比较。
     */
    public boolean isComparable(TypeCategory sourceCategory, TypeCategory targetCategory) {
        if (sourceCategory == TypeCategory.OTHER || targetCategory == TypeCategory.OTHER) {
            return false;
        }
        return sourceCategory == targetCategory;
    }

    /**
     * 根据类型分类格式化 Doris 目标表最大值，用于拼接到源端 SQL。
     *
     * @param maxValue 从目标表查到的最大值
     * @param category 目标端字段类型分类
     * @return 可直接拼入 SQL 的字符串字面量或数值字面量
     */
    public String formatMaxValue(Object maxValue, TypeCategory category) {
        if (maxValue == null) {
            return null;
        }
        return switch (category) {
            case NUMERIC -> formatNumericValue(maxValue);
            case TEMPORAL -> formatTemporalValue(maxValue);
            case OTHER -> "'" + escapeSqlString(maxValue.toString()) + "'";
        };
    }

    /**
     * 将数据库类型名分类为数值型、时间型或其他。
     */
    public TypeCategory classifyType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return TypeCategory.OTHER;
        }
        String lower = typeName.toLowerCase(Locale.ROOT);
        if (isNumeric(lower)) {
            return TypeCategory.NUMERIC;
        }
        if (isTemporal(lower)) {
            return TypeCategory.TEMPORAL;
        }
        return TypeCategory.OTHER;
    }

    private boolean isNumeric(String lowerType) {
        return lowerType.startsWith("int") || lowerType.startsWith("tinyint")
                || lowerType.startsWith("smallint") || lowerType.startsWith("mediumint")
                || lowerType.startsWith("bigint") || lowerType.startsWith("decimal")
                || lowerType.startsWith("numeric") || lowerType.startsWith("float")
                || lowerType.startsWith("double") || lowerType.startsWith("real")
                || lowerType.startsWith("number") || lowerType.startsWith("serial")
                || lowerType.equals("int2") || lowerType.equals("int4") || lowerType.equals("int8");
    }

    private boolean isTemporal(String lowerType) {
        return lowerType.startsWith("date") || lowerType.startsWith("time")
                || lowerType.startsWith("datetime") || lowerType.startsWith("timestamp")
                || lowerType.startsWith("year");
    }

    private String formatNumericValue(Object value) {
        if (value instanceof Number number) {
            return number.toString();
        }
        String str = value.toString().trim();
        if (!str.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            logger.warn("数值型增量字段最大值格式异常，将尝试按字符串比较: value={}", value);
            return "'" + escapeSqlString(str) + "'";
        }
        return str;
    }

    private String formatTemporalValue(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return "'" + ldt.format(DATETIME_FORMATTER) + "'";
        }
        if (value instanceof LocalDate ld) {
            return "'" + ld.format(DATE_FORMATTER) + "'";
        }
        if (value instanceof LocalTime lt) {
            return "'" + lt.format(TIME_FORMATTER) + "'";
        }
        if (value instanceof java.sql.Timestamp ts) {
            return "'" + ts.toLocalDateTime().format(DATETIME_FORMATTER) + "'";
        }
        if (value instanceof java.sql.Date date) {
            return "'" + date.toLocalDate().format(DATE_FORMATTER) + "'";
        }
        if (value instanceof java.sql.Time time) {
            return "'" + time.toLocalTime().format(TIME_FORMATTER) + "'";
        }
        String str = value.toString().trim();
        return "'" + escapeSqlString(str) + "'";
    }

    private String escapeSqlString(String value) {
        return value.replace("'", "''");
    }

    private String queryColumnTypeName(Connection connection, String dbType, String catalog,
                                       String schema, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String resolvedCatalog = resolveCatalog(dbType, catalog);
        String resolvedSchema = resolveSchema(dbType, schema);
        try (ResultSet rs = metaData.getColumns(resolvedCatalog, resolvedSchema, tableName, columnName)) {
            if (rs.next()) {
                return rs.getString("TYPE_NAME");
            }
        }
        return null;
    }

    private String resolveCatalog(String dbType, String catalog) {
        DataSourceType type = DataSourceType.fromCode(dbType);
        if (type == null) {
            return catalog;
        }
        return switch (type) {
            case POSTGRESQL, ORACLE, SQLSERVER -> catalog;
            case MYSQL, DORIS -> catalog;
        };
    }

    private String resolveSchema(String dbType, String schema) {
        DataSourceType type = DataSourceType.fromCode(dbType);
        if (type == null) {
            return null;
        }
        return switch (type) {
            case POSTGRESQL, ORACLE, SQLSERVER -> schema;
            case MYSQL, DORIS -> null;
        };
    }
}
