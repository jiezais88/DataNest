package com.datanest.system.service;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.datanest.system.config.SsoProperties;
import com.datanest.system.dto.SsoConfigVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSO / 认证安全配置读写（Sprint 14）。
 * <p>
 * 运行时维护 {@link SsoProperties} 缓存：启动时从 Nacos 加载，并注册配置变更监听实现热生效；
 * 在线保存走 Nacos publishConfig（写配置必须走发布 API，AGENTS.md 红线），并同步刷新本地缓存免等推送。
 * 解析用 snakeyaml（Spring 自带），回写用 Map 序列化保持 YAML 无类标签、与 shared-configs 风格一致。
 */
@Service
public class SsoConfigService {

    private static final Logger log = LoggerFactory.getLogger(SsoConfigService.class);
    private static final String DATA_ID = "sso-config.yaml";
    private static final String GROUP = "shared-configs";

    /** 兜底默认配置（Nacos 缺失/损坏时使用；与 shared-configs/sso-config.yaml 结构一致） */
    private static final String DEFAULT_YAML = """
            datanest:
              sso:
                enabled: false
                mode: mixed
                frontend-url: http://localhost:3000
                oidc:
                  enabled: false
                ldap:
                  enabled: false
                role-mapping:
                  default-role: DATA_ANALYST
                  rules: []
                password-policy:
                  min-length: 8
                  require-uppercase: true
                  require-lowercase: true
                  require-digit: true
                  require-special: false
                  expire-days: 90
                  warn-before-days: 7
                  fail-max: 5
                  lock-minutes: 30
            """;

    private final NacosConfigManager nacosConfigManager;
    private final Yaml yaml = new Yaml();

    private volatile SsoProperties current = new SsoProperties();

    public SsoConfigService(NacosConfigManager nacosConfigManager) {
        this.nacosConfigManager = nacosConfigManager;
    }

    @PostConstruct
    void init() {
        reload();
        try {
            nacosConfigManager.getConfigService().addListener(DATA_ID, GROUP, new AbstractListener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    reload();
                }
            });
        } catch (Exception e) {
            log.warn("注册 SSO 配置监听失败（热生效不可用，仍按启动值工作）: {}", e.getMessage());
        }
    }

    /** 当前生效的 SSO 配置（运行时读取入口） */
    public SsoProperties getSsoProperties() {
        return current;
    }

    /** 配置页读取（身份认证） */
    public SsoConfigVO readConfig() {
        return toVO(current);
    }

    /** 配置页保存：写 Nacos（发布 API）+ 立即刷新本地缓存 */
    public void saveConfig(SsoConfigVO vo) {
        SsoProperties props = toProperties(vo);
        String yamlText = toYaml(props);
        try {
            boolean published = nacosConfigManager.getConfigService()
                    .publishConfig(DATA_ID, GROUP, yamlText, "yaml");
            if (!published) {
                throw new IllegalStateException("Nacos publishConfig 返回 false（写入未生效）");
            }
        } catch (Exception e) {
            throw new RuntimeException("SSO 配置写入 Nacos 失败: " + e.getMessage(), e);
        }
        current = props;
        log.info("SSO 配置已保存并热生效: enabled={}, mode={}, oidc={}, ldap={}",
                props.isEnabled(), props.getMode(),
                props.getOidc() != null && props.getOidc().isEnabled(),
                props.getLdap() != null && props.getLdap().isEnabled());
    }

    private void reload() {
        try {
            String cfg = nacosConfigManager.getConfigService().getConfig(DATA_ID, GROUP, 5000);
            if (cfg == null || cfg.isBlank()) {
                cfg = DEFAULT_YAML;
            }
            current = parseYaml(cfg);
        } catch (Exception e) {
            // fail-open：读取失败保留旧值，不影响认证主链路
            log.warn("读取 SSO 配置失败，使用缓存/默认配置: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private SsoProperties parseYaml(String yamlText) {
        Map<String, Object> root = yaml.loadAs(yamlText, Map.class);
        Map<String, Object> datanest = (Map<String, Object>) root.get("datanest");
        Map<String, Object> sso = (Map<String, Object>) (datanest != null ? datanest.get("sso") : null);
        if (sso == null) {
            return new SsoProperties();
        }
        SsoProperties p = new SsoProperties();
        p.setEnabled(bool(sso, "enabled", false));
        p.setMode(str(sso, "mode", "mixed"));
        p.setFrontendUrl(str(sso, "frontend-url", "http://localhost:3000"));

        Map<String, Object> oidc = map(sso, "oidc");
        if (oidc != null) {
            SsoProperties.Oidc o = new SsoProperties.Oidc();
            o.setEnabled(bool(oidc, "enabled", false));
            o.setIssuer(str(oidc, "issuer", ""));
            o.setAuthorizationEndpoint(str(oidc, "authorization-endpoint", ""));
            o.setTokenEndpoint(str(oidc, "token-endpoint", ""));
            o.setJwksUri(str(oidc, "jwks-uri", ""));
            o.setClientId(str(oidc, "client-id", ""));
            o.setClientSecret(str(oidc, "client-secret", ""));
            o.setScope(str(oidc, "scope", "openid,profile,email"));
            o.setRedirectUri(str(oidc, "redirect-uri", ""));
            p.setOidc(o);
        }

        Map<String, Object> ldap = map(sso, "ldap");
        if (ldap != null) {
            SsoProperties.Ldap l = new SsoProperties.Ldap();
            l.setEnabled(bool(ldap, "enabled", false));
            l.setUrl(str(ldap, "url", ""));
            l.setBaseDn(str(ldap, "base-dn", ""));
            l.setBindDn(str(ldap, "bind-dn", ""));
            l.setBindPassword(str(ldap, "bind-password", ""));
            l.setUserFilter(str(ldap, "user-filter", "(&(objectClass=inetOrgPerson)(uid={0}))"));
            l.setUserSearchBase(str(ldap, "user-search-base", ""));
            l.setUsernameAttribute(str(ldap, "username-attribute", "uid"));
            l.setEmailAttribute(str(ldap, "email-attribute", "mail"));
            l.setDisplayNameAttribute(str(ldap, "display-name-attribute", "displayName"));
            l.setGroupAttribute(str(ldap, "group-attribute", "memberOf"));
            p.setLdap(l);
        }

        Map<String, Object> roleMapping = map(sso, "role-mapping");
        if (roleMapping != null) {
            SsoProperties.RoleMapping rm = new SsoProperties.RoleMapping();
            rm.setDefaultRole(str(roleMapping, "default-role", "DATA_ANALYST"));
            List<SsoProperties.Rule> rules = new ArrayList<>();
            Object rawRules = roleMapping.get("rules");
            if (rawRules instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        SsoProperties.Rule rule = new SsoProperties.Rule();
                        rule.setClaim(str(m, "claim", "groups"));
                        rule.setValue(str(m, "value", ""));
                        rule.setRoles(strList(m.get("roles")));
                        rules.add(rule);
                    }
                }
            }
            rm.setRules(rules);
            p.setRoleMapping(rm);
        }

        Map<String, Object> pp = map(sso, "password-policy");
        if (pp != null) {
            SsoProperties.PasswordPolicy policy = new SsoProperties.PasswordPolicy();
            policy.setMinLength(intVal(pp, "min-length", 8));
            policy.setRequireUppercase(bool(pp, "require-uppercase", true));
            policy.setRequireLowercase(bool(pp, "require-lowercase", true));
            policy.setRequireDigit(bool(pp, "require-digit", true));
            policy.setRequireSpecial(bool(pp, "require-special", false));
            policy.setExpireDays(intVal(pp, "expire-days", 90));
            policy.setWarnBeforeDays(intVal(pp, "warn-before-days", 7));
            policy.setFailMax(intVal(pp, "fail-max", 5));
            policy.setLockMinutes(intVal(pp, "lock-minutes", 30));
            p.setPasswordPolicy(policy);
        }
        return p;
    }

    private String toYaml(SsoProperties p) {
        Map<String, Object> sso = new LinkedHashMap<>();
        sso.put("enabled", p.isEnabled());
        sso.put("mode", p.getMode());
        sso.put("frontend-url", p.getFrontendUrl());

        Map<String, Object> oidc = new LinkedHashMap<>();
        oidc.put("enabled", p.getOidc() != null && p.getOidc().isEnabled());
        oidc.put("issuer", p.getOidc() != null ? p.getOidc().getIssuer() : "");
        oidc.put("authorization-endpoint", p.getOidc() != null ? p.getOidc().getAuthorizationEndpoint() : "");
        oidc.put("token-endpoint", p.getOidc() != null ? p.getOidc().getTokenEndpoint() : "");
        oidc.put("jwks-uri", p.getOidc() != null ? p.getOidc().getJwksUri() : "");
        oidc.put("client-id", p.getOidc() != null ? p.getOidc().getClientId() : "");
        oidc.put("client-secret", p.getOidc() != null ? p.getOidc().getClientSecret() : "");
        oidc.put("scope", p.getOidc() != null ? p.getOidc().getScope() : "openid,profile,email");
        oidc.put("redirect-uri", p.getOidc() != null ? p.getOidc().getRedirectUri() : "");
        sso.put("oidc", oidc);

        Map<String, Object> ldap = new LinkedHashMap<>();
        ldap.put("enabled", p.getLdap() != null && p.getLdap().isEnabled());
        ldap.put("url", p.getLdap() != null ? p.getLdap().getUrl() : "");
        ldap.put("base-dn", p.getLdap() != null ? p.getLdap().getBaseDn() : "");
        ldap.put("bind-dn", p.getLdap() != null ? p.getLdap().getBindDn() : "");
        ldap.put("bind-password", p.getLdap() != null ? p.getLdap().getBindPassword() : "");
        ldap.put("user-filter", p.getLdap() != null ? p.getLdap().getUserFilter() : "");
        ldap.put("user-search-base", p.getLdap() != null ? p.getLdap().getUserSearchBase() : "");
        ldap.put("username-attribute", p.getLdap() != null ? p.getLdap().getUsernameAttribute() : "uid");
        ldap.put("email-attribute", p.getLdap() != null ? p.getLdap().getEmailAttribute() : "mail");
        ldap.put("display-name-attribute", p.getLdap() != null ? p.getLdap().getDisplayNameAttribute() : "displayName");
        ldap.put("group-attribute", p.getLdap() != null ? p.getLdap().getGroupAttribute() : "memberOf");
        sso.put("ldap", ldap);

        Map<String, Object> roleMapping = new LinkedHashMap<>();
        roleMapping.put("default-role", p.getRoleMapping() != null ? p.getRoleMapping().getDefaultRole() : "DATA_ANALYST");
        List<Map<String, Object>> rules = new ArrayList<>();
        if (p.getRoleMapping() != null && p.getRoleMapping().getRules() != null) {
            for (SsoProperties.Rule r : p.getRoleMapping().getRules()) {
                Map<String, Object> ruleMap = new LinkedHashMap<>();
                ruleMap.put("claim", r.getClaim());
                ruleMap.put("value", r.getValue());
                ruleMap.put("roles", r.getRoles());
                rules.add(ruleMap);
            }
        }
        roleMapping.put("rules", rules);
        sso.put("role-mapping", roleMapping);

        SsoProperties.PasswordPolicy policy = p.getPasswordPolicy();
        Map<String, Object> pp = new LinkedHashMap<>();
        pp.put("min-length", policy != null ? policy.getMinLength() : 8);
        pp.put("require-uppercase", policy != null && policy.isRequireUppercase());
        pp.put("require-lowercase", policy != null && policy.isRequireLowercase());
        pp.put("require-digit", policy != null && policy.isRequireDigit());
        pp.put("require-special", policy != null && policy.isRequireSpecial());
        pp.put("expire-days", policy != null ? policy.getExpireDays() : 90);
        pp.put("warn-before-days", policy != null ? policy.getWarnBeforeDays() : 7);
        pp.put("fail-max", policy != null ? policy.getFailMax() : 5);
        pp.put("lock-minutes", policy != null ? policy.getLockMinutes() : 30);
        sso.put("password-policy", pp);

        Map<String, Object> datanest = new LinkedHashMap<>();
        datanest.put("sso", sso);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("datanest", datanest);
        return yaml.dump(root);
    }

    private SsoConfigVO toVO(SsoProperties p) {
        SsoProperties.Oidc oidc = p.getOidc();
        SsoProperties.Ldap ldap = p.getLdap();
        SsoProperties.RoleMapping rm = p.getRoleMapping();
        SsoProperties.PasswordPolicy pp = p.getPasswordPolicy();

        List<SsoConfigVO.RuleVO> ruleVOs = new ArrayList<>();
        if (rm != null && rm.getRules() != null) {
            for (SsoProperties.Rule r : rm.getRules()) {
                ruleVOs.add(new SsoConfigVO.RuleVO(r.getClaim(), r.getValue(), r.getRoles()));
            }
        }
        return new SsoConfigVO(
                p.isEnabled(), p.getMode(), p.getFrontendUrl(),
                new SsoConfigVO.OidcVO(oidc != null && oidc.isEnabled(),
                        oidc != null ? oidc.getIssuer() : null,
                        oidc != null ? oidc.getAuthorizationEndpoint() : null,
                        oidc != null ? oidc.getTokenEndpoint() : null,
                        oidc != null ? oidc.getJwksUri() : null,
                        oidc != null ? oidc.getClientId() : null,
                        oidc != null ? oidc.getClientSecret() : null,
                        oidc != null ? oidc.getScope() : null,
                        oidc != null ? oidc.getRedirectUri() : null),
                new SsoConfigVO.LdapVO(ldap != null && ldap.isEnabled(),
                        ldap != null ? ldap.getUrl() : null,
                        ldap != null ? ldap.getBaseDn() : null,
                        ldap != null ? ldap.getBindDn() : null,
                        ldap != null ? ldap.getBindPassword() : null,
                        ldap != null ? ldap.getUserFilter() : null,
                        ldap != null ? ldap.getUserSearchBase() : null,
                        ldap != null ? ldap.getUsernameAttribute() : null,
                        ldap != null ? ldap.getEmailAttribute() : null,
                        ldap != null ? ldap.getDisplayNameAttribute() : null,
                        ldap != null ? ldap.getGroupAttribute() : null),
                new SsoConfigVO.RoleMappingVO(rm != null ? rm.getDefaultRole() : null, ruleVOs),
                new SsoConfigVO.PasswordPolicyVO(
                        pp != null ? pp.getMinLength() : null,
                        pp != null ? pp.isRequireUppercase() : null,
                        pp != null ? pp.isRequireLowercase() : null,
                        pp != null ? pp.isRequireDigit() : null,
                        pp != null ? pp.isRequireSpecial() : null,
                        pp != null ? pp.getExpireDays() : null,
                        pp != null ? pp.getWarnBeforeDays() : null,
                        pp != null ? pp.getFailMax() : null,
                        pp != null ? pp.getLockMinutes() : null)
        );
    }

    private SsoProperties toProperties(SsoConfigVO vo) {
        SsoProperties p = new SsoProperties();
        p.setEnabled(Boolean.TRUE.equals(vo.enabled()));
        p.setMode(vo.mode() == null || vo.mode().isBlank() ? "mixed" : vo.mode());
        p.setFrontendUrl(vo.frontendUrl() == null ? "http://localhost:3000" : vo.frontendUrl());

        if (vo.oidc() != null) {
            SsoProperties.Oidc o = new SsoProperties.Oidc();
            o.setEnabled(Boolean.TRUE.equals(vo.oidc().enabled()));
            o.setIssuer(nvl(vo.oidc().issuer()));
            o.setAuthorizationEndpoint(nvl(vo.oidc().authorizationEndpoint()));
            o.setTokenEndpoint(nvl(vo.oidc().tokenEndpoint()));
            o.setJwksUri(nvl(vo.oidc().jwksUri()));
            o.setClientId(nvl(vo.oidc().clientId()));
            o.setClientSecret(nvl(vo.oidc().clientSecret()));
            o.setScope(nvl(vo.oidc().scope()));
            o.setRedirectUri(nvl(vo.oidc().redirectUri()));
            p.setOidc(o);
        }
        if (vo.ldap() != null) {
            SsoProperties.Ldap l = new SsoProperties.Ldap();
            l.setEnabled(Boolean.TRUE.equals(vo.ldap().enabled()));
            l.setUrl(nvl(vo.ldap().url()));
            l.setBaseDn(nvl(vo.ldap().baseDn()));
            l.setBindDn(nvl(vo.ldap().bindDn()));
            l.setBindPassword(nvl(vo.ldap().bindPassword()));
            l.setUserFilter(nvl(vo.ldap().userFilter()));
            l.setUserSearchBase(nvl(vo.ldap().userSearchBase()));
            l.setUsernameAttribute(nvl(vo.ldap().usernameAttribute()));
            l.setEmailAttribute(nvl(vo.ldap().emailAttribute()));
            l.setDisplayNameAttribute(nvl(vo.ldap().displayNameAttribute()));
            l.setGroupAttribute(nvl(vo.ldap().groupAttribute()));
            p.setLdap(l);
        }
        if (vo.roleMapping() != null) {
            SsoProperties.RoleMapping rm = new SsoProperties.RoleMapping();
            rm.setDefaultRole(vo.roleMapping().defaultRole() == null || vo.roleMapping().defaultRole().isBlank()
                    ? "DATA_ANALYST" : vo.roleMapping().defaultRole());
            List<SsoProperties.Rule> rules = new ArrayList<>();
            if (vo.roleMapping().rules() != null) {
                for (SsoConfigVO.RuleVO r : vo.roleMapping().rules()) {
                    SsoProperties.Rule rule = new SsoProperties.Rule();
                    rule.setClaim(nvl(r.claim()));
                    rule.setValue(nvl(r.value()));
                    rule.setRoles(r.roles() == null ? new ArrayList<>() : r.roles());
                    rules.add(rule);
                }
            }
            rm.setRules(rules);
            p.setRoleMapping(rm);
        }
        if (vo.passwordPolicy() != null) {
            SsoProperties.PasswordPolicy pp = new SsoProperties.PasswordPolicy();
            pp.setMinLength(vo.passwordPolicy().minLength() != null ? vo.passwordPolicy().minLength() : 8);
            pp.setRequireUppercase(!Boolean.FALSE.equals(vo.passwordPolicy().requireUppercase()));
            pp.setRequireLowercase(!Boolean.FALSE.equals(vo.passwordPolicy().requireLowercase()));
            pp.setRequireDigit(!Boolean.FALSE.equals(vo.passwordPolicy().requireDigit()));
            pp.setRequireSpecial(Boolean.TRUE.equals(vo.passwordPolicy().requireSpecial()));
            pp.setExpireDays(vo.passwordPolicy().expireDays() != null ? vo.passwordPolicy().expireDays() : 90);
            pp.setWarnBeforeDays(vo.passwordPolicy().warnBeforeDays() != null ? vo.passwordPolicy().warnBeforeDays() : 7);
            pp.setFailMax(vo.passwordPolicy().failMax() != null ? vo.passwordPolicy().failMax() : 5);
            pp.setLockMinutes(vo.passwordPolicy().lockMinutes() != null ? vo.passwordPolicy().lockMinutes() : 30);
            p.setPasswordPolicy(pp);
        }
        return p;
    }

    // ---------- helpers ----------
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static boolean bool(Map<?, ?> m, String key, boolean def) {
        Object v = m.get(key);
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    private static int intVal(Map<?, ?> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return new ArrayList<>();
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }
}
