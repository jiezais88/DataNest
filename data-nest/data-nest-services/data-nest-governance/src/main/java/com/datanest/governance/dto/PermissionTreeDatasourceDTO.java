package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限配置树——数据源节点（Sprint 11 F2）。
 * <p>
 * 一次性返回某角色可配置的数据权限白名单树（数据源→库→表三级，schema 已在后端展平去重），
 * 供权限配置页全量渲染，避免前端逐数据源/库/表循环远程调用（N+1）。
 */
@Schema(description = "权限配置树-数据源节点")
@Data
public class PermissionTreeDatasourceDTO {

    @Schema(description = "数据源 ID")
    private Long datasourceId;

    @Schema(description = "数据源名称")
    private String datasourceName;

    @Schema(description = "数据源类型（如 MYSQL/POSTGRESQL）")
    private String datasourceType;

    @Schema(description = "库列表")
    private List<PermissionTreeDatabaseDTO> databases = new ArrayList<>();
}
