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
        return buildJdbcUrl(type, host, port, database, schema, 10);
    }

    /**
     * 构建 JDBC URL（可指定 socket 超时秒数）。
     *
     * @param socketTimeoutSeconds socket 超时秒数（默认 10，同步任务沿用；SQL 终端可传入请求级超时，
     *                             避免 queryTimeout 被 10s socket 超时提前截断），上限 300s
     *                             <p>
     *                             2026-08-12 新增：收敛 data-service ExternalSqlExecutor 自写的
     *                             buildJdbcUrl（原实现与 JdbcUrlBuilder 仅差可配 socketTimeout，现统一委托此处）。
     */
    public static String buildJdbcUrl(String type, String host, int port, String database, String schema,
                                      int socketTimeoutSeconds) {
        DataSourceType dataSourceType = DataSourceType.fromCode(type);
        if (dataSourceType == null) {
            throw new IllegalArgumentException("Unsupported data source type: " + type);
        }
        int socketMs = Math.min(Math.max(socketTimeoutSeconds, 1), 300) * 1000;
        return switch (dataSourceType) {
            case MYSQL -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=%d",
                    host, port, database, socketMs);
            case DORIS -> String.format(
                    "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=%d",
                    host, port, database, socketMs);
            case POSTGRESQL -> {
                if (schema != null && !schema.isBlank()) {
                    yield String.format(
                            "jdbc:postgresql://%s:%d/%s?currentSchema=%s&connectTimeout=10&socketTimeout=%d",
                            host, port, database, schema, socketMs);
                }
                yield String.format(
                        "jdbc:postgresql://%s:%d/%s?connectTimeout=10&socketTimeout=%d",
                        host, port, database, socketMs);
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
