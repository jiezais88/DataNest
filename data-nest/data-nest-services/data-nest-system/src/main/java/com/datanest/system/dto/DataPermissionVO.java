package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 角色数据权限记录视图对象（权限配置页回显）。
 */
@Schema(description = "角色数据权限记录视图对象")
public record DataPermissionVO(
        @Schema(description = "记录 ID") Long id,
        @Schema(description = "数据源 ID") Long datasourceId,
        @Schema(description = "数据库名（空=库级通配）") String databaseName,
        @Schema(description = "表名（空=表级通配）") String tableName
) {
}
