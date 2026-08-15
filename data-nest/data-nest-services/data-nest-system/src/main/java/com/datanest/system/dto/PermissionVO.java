package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 权限点视图对象（供角色管理页功能权限勾选树）。
 * <p>
 * code 规范「模块:动作」，前端按 code 冒号前缀分组为模块勾选树。
 */
@Schema(description = "权限点视图对象")
public record PermissionVO(
        @Schema(description = "权限点 ID") Long id,
        @Schema(description = "权限点编码（模块:动作）") String code,
        @Schema(description = "权限点名称") String name,
        @Schema(description = "权限点描述") String description
) {
}
