package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * LDAP 域账号登录请求（Sprint 14 SSO）。
 */
@Schema(description = "LDAP 域账号登录请求")
public record LdapLoginRequest(
        @Schema(description = "域账号（用户名）", example = "zhangsan") @NotBlank String username,
        @Schema(description = "域账号密码") @NotBlank String password
) {
}
