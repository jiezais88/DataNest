package com.datanest.common.model;

/**
 * 数据权限三级白名单授权项（Sprint 11 F2，技术文档 D-2）。
 * <p>
 * {@code databaseName}/{@code tableName} 可空：
 * 数据源级（两者皆空）= 该数据源全量；库级（仅 tableName 空）= 该库全量；表级 = 精确单表。
 * 跨服务传输 DTO，见 system 内部端点与 {@code SystemPermissionApi}。
 */
public record DataPermissionGrant(
        Long datasourceId,
        String databaseName,
        String tableName
) {
}
