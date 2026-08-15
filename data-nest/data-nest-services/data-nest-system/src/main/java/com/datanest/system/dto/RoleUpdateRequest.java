package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 自定义角色编辑请求（Sprint 11 F2）。
 * <p>
 * 角色名称/编码创建后不可修改（PRD §6.2.1），仅可改描述与功能权限。
 */
@Schema(description = "自定义角色编辑请求")
public record RoleUpdateRequest(
        @Schema(description = "角色描述（不超过 100 字）")
        @Size(max = 100) String description,
        @Schema(description = "功能权限点编码列表（至少一项）", example = "[\"asset:view\"]")
        @NotEmpty List<String> permissions
) {
}
