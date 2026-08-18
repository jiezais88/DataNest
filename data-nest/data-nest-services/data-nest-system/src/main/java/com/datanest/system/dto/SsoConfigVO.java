package com.datanest.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 身份认证配置视图对象（「身份认证」页读写契约）。
 * <p>
 * 与 {@code datanest.sso.*} 一一对应；保存时由后端序列化为 YAML 写入 Nacos 热生效。
 */
@Schema(description = "身份认证配置（SSO/LDAP/角色映射/密码策略）")
public record SsoConfigVO(
        @Schema(description = "SSO 总开关") Boolean enabled,
        @Schema(description = "登录模式：mixed/sso-only") String mode,
        @Schema(description = "前端地址（回调重定向目标）") String frontendUrl,
        @Schema(description = "OIDC 配置") OidcVO oidc,
        @Schema(description = "LDAP 配置") LdapVO ldap,
        @Schema(description = "角色映射") RoleMappingVO roleMapping,
        @Schema(description = "密码策略") PasswordPolicyVO passwordPolicy
) {
    public record OidcVO(
            @Schema(description = "是否启用 OIDC") Boolean enabled,
            @Schema(description = "IdP 发行方") String issuer,
            @Schema(description = "授权端点（空则 Discovery）") String authorizationEndpoint,
            @Schema(description = "令牌端点（空则 Discovery）") String tokenEndpoint,
            @Schema(description = "JWKS 端点（空则 Discovery）") String jwksUri,
            @Schema(description = "客户端 ID") String clientId,
            @Schema(description = "客户端密钥") String clientSecret,
            @Schema(description = "请求 scope") String scope,
            @Schema(description = "回调地址") String redirectUri
    ) {}

    public record LdapVO(
            @Schema(description = "是否启用 LDAP") Boolean enabled,
            @Schema(description = "LDAP 地址（ldap://host:389）") String url,
            @Schema(description = "基础 DN") String baseDn,
            @Schema(description = "管理绑定 DN") String bindDn,
            @Schema(description = "管理绑定密码") String bindPassword,
            @Schema(description = "用户过滤器（{0} 为用户名）") String userFilter,
            @Schema(description = "用户搜索基") String userSearchBase,
            @Schema(description = "用户名属性") String usernameAttribute,
            @Schema(description = "邮箱属性") String emailAttribute,
            @Schema(description = "显示名属性") String displayNameAttribute,
            @Schema(description = "组属性（memberOf 等多值）") String groupAttribute
    ) {}

    public record RoleMappingVO(
            @Schema(description = "默认角色（未命中规则时）") String defaultRole,
            @Schema(description = "映射规则列表") List<RuleVO> rules
    ) {}

    public record RuleVO(
            @Schema(description = "claim 字段名") String claim,
            @Schema(description = "命中值") String value,
            @Schema(description = "授予的平台角色编码列表") List<String> roles
    ) {}

    public record PasswordPolicyVO(
            @Schema(description = "最小长度") Integer minLength,
            @Schema(description = "需大写字母") Boolean requireUppercase,
            @Schema(description = "需小写字母") Boolean requireLowercase,
            @Schema(description = "需数字") Boolean requireDigit,
            @Schema(description = "需特殊字符") Boolean requireSpecial,
            @Schema(description = "过期天数（0=不过期）") Integer expireDays,
            @Schema(description = "过期前提醒天数") Integer warnBeforeDays,
            @Schema(description = "连续失败锁定阈值") Integer failMax,
            @Schema(description = "锁定分钟数") Integer lockMinutes
    ) {}
}
