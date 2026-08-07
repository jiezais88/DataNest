package com.datanest.common.util;

import com.datanest.common.constant.DataSourceType;

/**
 * JDBC URL 构建工具（按数据源类型拼连接串，含超时参数）。
 * <p>
 * 微服务化 4.3：原 ConnectionTester.buildJdbcUrl 逻辑下沉 common，
 * 供 task-core（Addax/Generic 执行器）与 engineering（ConnectionTester）共用。
 */
public final class JdbcUrlBuilder {

    private JdbcUrlBuilder() {
    }

    public static String buildJdbcUrl(String type, String host, int port, String database, String schema) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        return switch (dataSourceType) {
            case MYSQL -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                    host, port, database);
            case DORIS -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=10000",
                    host, port, database);
            case POSTGRESQL -> {
                if (schema != null && !schema.isBlank()) {
                    yield String.format(
                            "jdbc:postgresql://%s:%d/%s?currentSchema=%s&connectTimeout=10&socketTimeout=10",
                            host, port, database, schema);
                }
                yield String.format(
                        "jdbc:postgresql://%s:%d/%s?connectTimeout=10&socketTimeout=10",
                        host, port, database);
            }
            case ORACLE -> String.format(
                    "jdbc:oracle:thin:@//%s:%d/%s",
                    host, port, database);
            case SQLSERVER -> String.format(
                    "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false;trustServerCertificate=true;loginTimeout=10",
                    host, port, database);
        };
    }
}
