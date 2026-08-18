package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SSO 登录页初始化状态（公开接口，供登录页决定是否显示企业身份入口）。
 */
@Schema(description = "SSO 登录页状态")
public record SsoStatusVO(
        @Schema(description = "SSO 总开关") boolean enabled,
        @Schema(description = "登录模式：mixed/sso-only") String mode,
        @Schema(description = "OIDC 是否启用") boolean oidcEnabled,
        @Schema(description = "LDAP 是否启用") boolean ldapEnabled
) {
}
