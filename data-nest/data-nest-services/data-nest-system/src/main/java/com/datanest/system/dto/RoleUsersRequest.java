package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 设置角色成员请求（Sprint 11 F2，权限配置页成员 Tab）。
 * <p>
 * 全量替换语义：仅替换该角色（role_id）的用户关联，不影响用户其他角色绑定。
 */
@Schema(description = "设置角色成员请求")
public record RoleUsersRequest(
        @Schema(description = "用户 ID 列表（全量替换该角色成员）") @NotNull List<Long> userIds
) {
}
