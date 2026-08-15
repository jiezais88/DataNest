package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色视图对象（角色管理页列表/详情）。
 */
@Schema(description = "角色视图对象")
public record RoleVO(
        @Schema(description = "角色 ID") Long id,
        @Schema(description = "角色编码（预置/自定义均英文可读）") String code,
        @Schema(description = "角色名称") String name,
        @Schema(description = "角色描述") String description,
        @Schema(description = "是否预置角色（预置只读不可删）") boolean builtin,
        @Schema(description = "功能权限点编码列表") List<String> permissions,
        @Schema(description = "数据权限默认范围（FULL=全部数据 / WHITELIST=仅授权数据）") String dataScope,
        @Schema(description = "创建时间") LocalDateTime createdAt
) {
}
