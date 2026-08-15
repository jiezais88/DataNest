package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 自定义角色创建请求（Sprint 11 F2）。
 * <p>
 * code 管理员填写可读英文（对齐预置角色风格，唯一约束 uk_sys_role_code）；
 * name 为中文显示名（唯一，创建后不可修改）。
 */
@Schema(description = "自定义角色创建请求")
public record RoleCreateRequest(
        @Schema(description = "角色名称（2~20 字符，全局唯一，创建后不可修改）", example = "只读审计员")
        @NotBlank @Size(min = 2, max = 20) String name,
        @Schema(description = "角色编码（英文可读，字母开头，字母/数字/下划线）", example = "READONLY_AUDITOR")
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,29}", message = "角色编码只能包含字母、数字、下划线，以字母开头")
        String code,
        @Schema(description = "角色描述（不超过 100 字）")
        @Size(max = 100) String description,
        @Schema(description = "功能权限点编码列表（至少一项）", example = "[\"asset:view\"]")
        @NotEmpty List<String> permissions
) {
}
