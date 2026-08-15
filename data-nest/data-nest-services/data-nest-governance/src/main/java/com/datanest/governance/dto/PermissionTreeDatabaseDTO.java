package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限配置树——库节点（Sprint 11 F2）。
 * <p>
 * 表名已跨 schema 去重（数据权限粒度是「数据源+库+表」，不含 schema，见 PRD §6.2.2 / D2）。
 * 每个表携带敏感度（PM-6），供前端对机密表显示锁定图标并禁用勾选。
 */
@Schema(description = "权限配置树-库节点")
@Data
public class PermissionTreeDatabaseDTO {

    @Schema(description = "数据库名")
    private String databaseName;

    @Schema(description = "表节点列表（已跨 schema 去重；含敏感度，PM-6）")
    private List<PermissionTreeTableDTO> tables = new ArrayList<>();
}
