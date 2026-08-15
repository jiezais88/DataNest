package com.datanest.governance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 权限配置树——表节点（Sprint 11 F2 / PM-6）。
 * <p>
 * 携带敏感度（sensitivityLevel），前端对 CONFIDENTIAL 表显示锁定图标、禁用勾选并提示先降级。
 */
@Schema(description = "权限配置树-表节点")
@Data
public class PermissionTreeTableDTO {

    @Schema(description = "表名（已跨 schema 去重）")
    private String tableName;

    @Schema(description = "数据敏感度（PUBLIC 公开 / INTERNAL 内部 / CONFIDENTIAL 机密；未打标默认 PUBLIC）")
    private String sensitivityLevel;
}
