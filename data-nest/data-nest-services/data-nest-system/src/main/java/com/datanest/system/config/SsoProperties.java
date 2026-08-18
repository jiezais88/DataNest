package com.datanest.system.config;

import java.util.ArrayList;
import java.util.List;

/**
 * SSO / 认证安全配置（Sprint 14）POJO。
 * <p>
 * 由 {@link SsoConfigService} 从 Nacos sso-config.yaml 解析维护（单一配置源），
 * 配置可在「身份认证」页在线编辑，保存后经 Nacos publishConfig 热生效（无需重启）。
 * 可变 JavaBean：同时作为 snakeyaml 序列化回写 Nacos 的模型。
 */
public class SsoProperties {

    /** SSO 总开关：false 时 SSO/LDAP 入口不可用，本地登录完全不受影响（存量兼容） */
    private boolean enabled = false;
    /** 登录模式：mixed（本地 + 企业身份）/ sso-only（仅企业身份，admin 本地登录保底） */
    private String mode = "mixed";
    /** 前端地址（SSO 回调后 302 重定向目标，带 token fragment） */
    private String frontendUrl = "http://localhost:3000";

    private Oidc oidc = new Oidc();
    private Ldap ldap = new Ldap();
    private RoleMapping roleMapping = new RoleMapping();
    private PasswordPolicy passwordPolicy = new PasswordPolicy();

    /** OIDC 授权码配置 */
    public static class Oidc {
        private boolean enabled = false;
        private String issuer = "";
        /** 授权端点；为空时走 OIDC Discovery（issuer/.well-known/openid-configuration） */
        private String authorizationEndpoint = "";
        private String tokenEndpoint = "";
        private String jwksUri = "";
        private String clientId = "";
        private String clientSecret = "";
        private String scope = "openid,profile,email";
        private String redirectUri = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAuthorizationEndpoint() { return authorizationEndpoint; }
        public void setAuthorizationEndpoint(String authorizationEndpoint) { this.authorizationEndpoint = authorizationEndpoint; }
        public String getTokenEndpoint() { return tokenEndpoint; }
        public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }
        public String getJwksUri() { return jwksUri; }
        public void setJwksUri(String jwksUri) { this.jwksUri = jwksUri; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    }

    /** LDAP 配置 */
    public static class Ldap {
        private boolean enabled = false;
        private String url = "";
        private String baseDn = "";
        private String bindDn = "";
        private String bindPassword = "";
        /** 用户查询过滤器，{0} 替换为登录用户名；同步时替换为 *（全量拉取） */
        private String userFilter = "(&(objectClass=inetOrgPerson)(uid={0}))";
        private String userSearchBase = "";
        private String usernameAttribute = "uid";
        private String emailAttribute = "mail";
        private String displayNameAttribute = "displayName";
        private String groupAttribute = "memberOf";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getBaseDn() { return baseDn; }
        public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
        public String getBindDn() { return bindDn; }
        public void setBindDn(String bindDn) { this.bindDn = bindDn; }
        public String getBindPassword() { return bindPassword; }
        public void setBindPassword(String bindPassword) { this.bindPassword = bindPassword; }
        public String getUserFilter() { return userFilter; }
        public void setUserFilter(String userFilter) { this.userFilter = userFilter; }
        public String getUserSearchBase() { return userSearchBase; }
        public void setUserSearchBase(String userSearchBase) { this.userSearchBase = userSearchBase; }
        public String getUsernameAttribute() { return usernameAttribute; }
        public void setUsernameAttribute(String usernameAttribute) { this.usernameAttribute = usernameAttribute; }
        public String getEmailAttribute() { return emailAttribute; }
        public void setEmailAttribute(String emailAttribute) { this.emailAttribute = emailAttribute; }
        public String getDisplayNameAttribute() { return displayNameAttribute; }
        public void setDisplayNameAttribute(String displayNameAttribute) { this.displayNameAttribute = displayNameAttribute; }
        public String getGroupAttribute() { return groupAttribute; }
        public void setGroupAttribute(String groupAttribute) { this.groupAttribute = groupAttribute; }
    }

    /** 角色映射（D3 默认角色 + D4 规则映射） */
    public static class RoleMapping {
        private String defaultRole = "DATA_ANALYST";
        private List<Rule> rules = new ArrayList<>();

        public String getDefaultRole() { return defaultRole; }
        public void setDefaultRole(String defaultRole) { this.defaultRole = defaultRole; }
        public List<Rule> getRules() { return rules; }
        public void setRules(List<Rule> rules) { this.rules = rules; }
    }

    /** 单条映射规则：IdP 的 claim 值命中 value 时授予 roles */
    public static class Rule {
        private String claim = "groups";
        private String value = "";
        private List<String> roles = new ArrayList<>();

        public String getClaim() { return claim; }
        public void setClaim(String claim) { this.claim = claim; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }

    /** 密码策略（仅 LOCAL 用户；SSO 用户不受限） */
    public static class PasswordPolicy {
        private int minLength = 8;
        private boolean requireUppercase = true;
        private boolean requireLowercase = true;
        private boolean requireDigit = true;
        private boolean requireSpecial = false;
        /** 过期天数；0 = 不过期 */
        private int expireDays = 90;
        private int warnBeforeDays = 7;
        private int failMax = 5;
        private int lockMinutes = 30;

        public int getMinLength() { return minLength; }
        public void setMinLength(int minLength) { this.minLength = minLength; }
        public boolean isRequireUppercase() { return requireUppercase; }
        public void setRequireUppercase(boolean requireUppercase) { this.requireUppercase = requireUppercase; }
        public boolean isRequireLowercase() { return requireLowercase; }
        public void setRequireLowercase(boolean requireLowercase) { this.requireLowercase = requireLowercase; }
        public boolean isRequireDigit() { return requireDigit; }
        public void setRequireDigit(boolean requireDigit) { this.requireDigit = requireDigit; }
        public boolean isRequireSpecial() { return requireSpecial; }
        public void setRequireSpecial(boolean requireSpecial) { this.requireSpecial = requireSpecial; }
        public int getExpireDays() { return expireDays; }
        public void setExpireDays(int expireDays) { this.expireDays = expireDays; }
        public int getWarnBeforeDays() { return warnBeforeDays; }
        public void setWarnBeforeDays(int warnBeforeDays) { this.warnBeforeDays = warnBeforeDays; }
        public int getFailMax() { return failMax; }
        public void setFailMax(int failMax) { this.failMax = failMax; }
        public int getLockMinutes() { return lockMinutes; }
        public void setLockMinutes(int lockMinutes) { this.lockMinutes = lockMinutes; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getFrontendUrl() { return frontendUrl; }
    public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
    public Oidc getOidc() { return oidc; }
    public void setOidc(Oidc oidc) { this.oidc = oidc; }
    public Ldap getLdap() { return ldap; }
    public void setLdap(Ldap ldap) { this.ldap = ldap; }
    public RoleMapping getRoleMapping() { return roleMapping; }
    public void setRoleMapping(RoleMapping roleMapping) { this.roleMapping = roleMapping; }
    public PasswordPolicy getPasswordPolicy() { return passwordPolicy; }
    public void setPasswordPolicy(PasswordPolicy passwordPolicy) { this.passwordPolicy = passwordPolicy; }

    /** 是否为仅企业身份登录模式 */
    public boolean isSsoOnlyMode() {
        return "sso-only".equals(mode);
    }
}
