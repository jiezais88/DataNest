package com.datanest.system.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.system.config.SsoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * LDAP 客户端（Sprint 14）。
 * <p>
 * 编程式 JNDI（未配 spring.ldap.urls 时 Boot 的 LdapAutoConfiguration 不生效，无冲突）：
 * ① 管理连接（bind-dn/bind-password）搜索用户 → ② 用户 DN + 密码二次 bind 验证 →
 * ③ 提取用户名/邮箱/显示名/memberOf 组（组提取 CN 后供角色映射）。
 * 支持匿名管理连接（bind-dn 留空）。
 */
@Service
public class LdapClientService {

    private static final Logger log = LoggerFactory.getLogger(LdapClientService.class);

    private final SsoConfigService ssoConfigService;

    public LdapClientService(SsoConfigService ssoConfigService) {
        this.ssoConfigService = ssoConfigService;
    }

    /** 域账号密码认证：成功返回用户信息，失败抛 LDAP_AUTH_FAILED / LDAP_CONNECTION_FAILED */
    public LdapUserInfo authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.LDAP_AUTH_FAILED);
        }
        SsoProperties.Ldap ldap = requireLdap();
        DirContext admin = null;
        DirContext userCtx = null;
        try {
            admin = bind(ldap, ldap.getBindDn(), ldap.getBindPassword());
            SearchResult sr = findUser(admin, ldap, username);
            if (sr == null) {
                throw new BusinessException(ErrorCode.LDAP_AUTH_FAILED);
            }
            LdapUserInfo info = extract(ldap, sr);
            // 用户 DN + 密码二次 bind 验证（javax.naming DirContext 非 AutoCloseable，用 finally 释放）
            try {
                userCtx = bind(ldap, info.dn(), password);
            } catch (AuthenticationException e) {
                throw new BusinessException(ErrorCode.LDAP_AUTH_FAILED);
            }
            log.info("LDAP 认证成功 username={}, dn={}, email={}, groups={}",
                    info.username(), info.dn(), info.email(), info.groups());
            return info;
        } catch (BusinessException e) {
            throw e;
        } catch (AuthenticationException e) {
            throw new BusinessException(ErrorCode.LDAP_AUTH_FAILED);
        } catch (NamingException e) {
            log.warn("LDAP 连接/搜索异常 url={}: {}", ldap.getUrl(), e.getMessage());
            throw new BusinessException(ErrorCode.LDAP_CONNECTION_FAILED);
        } finally {
            closeQuietly(admin);
            closeQuietly(userCtx);
        }
    }

    /** 全量拉取目录用户（同步用：user-filter 的 {0} 替换为 *） */
    public List<LdapUserInfo> searchAllUsers() {
        SsoProperties.Ldap ldap = requireLdap();
        List<LdapUserInfo> result = new ArrayList<>();
        DirContext admin = null;
        try {
            admin = bind(ldap, ldap.getBindDn(), ldap.getBindPassword());
            String filter = ldap.getUserFilter() == null || ldap.getUserFilter().isBlank()
                    ? "(objectClass=*)"
                    : ldap.getUserFilter().replace("{0}", "*");
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            // memberOf 多数 OpenLDAP 实现为 operational 属性，需显式请求
            sc.setReturningAttributes(new String[]{"*", nvl(ldap.getGroupAttribute(), "memberOf")});
            NamingEnumeration<SearchResult> results =
                    admin.search(base(ldap), filter, null, sc);
            while (results.hasMore()) {
                result.add(extract(ldap, results.next()));
            }
        } catch (NamingException e) {
            log.warn("LDAP 全量拉取失败 url={}: {}", ldap.getUrl(), e.getMessage());
            throw new BusinessException(ErrorCode.LDAP_SYNC_FAILED);
        } finally {
            closeQuietly(admin);
        }
        return result;
    }

    private void closeQuietly(DirContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (NamingException ignored) {
                // 忽略关闭异常
            }
        }
    }

    private SearchResult findUser(DirContext ctx, SsoProperties.Ldap ldap, String username) throws NamingException {
        String filter = ldap.getUserFilter() == null || ldap.getUserFilter().isBlank()
                ? "(uid={0})" : ldap.getUserFilter();
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        // memberOf 在多数 OpenLDAP 实现中是 operational 属性，默认搜索不返回，需显式请求
        sc.setReturningAttributes(new String[]{"*", nvl(ldap.getGroupAttribute(), "memberOf")});
        NamingEnumeration<SearchResult> results = ctx.search(base(ldap), filter, new Object[]{username}, sc);
        return results.hasMore() ? results.next() : null;
    }

    private DirContext bind(SsoProperties.Ldap ldap, String principal, String credentials) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        // Provider URL 拼 base DN：JNDI 相对名（如 user-search-base=ou=people）基于该 base 解析，
        // 否则相对 root DSE 解析返回 LDAP error 32 No Such Object
        env.put(Context.PROVIDER_URL, providerUrl(ldap));
        if (principal != null && !principal.isBlank()) {
            env.put(Context.SECURITY_PRINCIPAL, principal);
            env.put(Context.SECURITY_CREDENTIALS, credentials == null ? "" : credentials);
        }
        return new InitialDirContext(env);
    }

    /** url（如 ldap://host:389）末尾拼 base-dn；若 URL 已含路径则不重复拼 */
    private String providerUrl(SsoProperties.Ldap ldap) {
        String url = ldap.getUrl();
        if (url == null || url.isBlank()) {
            return url;
        }
        if (url.contains("/") && url.indexOf('/') > url.indexOf("://") + 2) {
            return url; // 已含路径
        }
        String baseDn = ldap.getBaseDn();
        if (baseDn == null || baseDn.isBlank()) {
            return url;
        }
        return url.replaceAll("/+$", "") + "/" + baseDn;
    }

    private String base(SsoProperties.Ldap ldap) {
        return ldap.getUserSearchBase() == null || ldap.getUserSearchBase().isBlank()
                ? "" : ldap.getUserSearchBase();
    }

    private LdapUserInfo extract(SsoProperties.Ldap ldap, SearchResult sr) throws NamingException {
        String dn = sr.getNameInNamespace();
        Attributes attrs = sr.getAttributes();
        String username = attr(attrs, ldap.getUsernameAttribute());
        if (username == null || username.isBlank()) {
            username = firstRdn(dn, ldap.getUsernameAttribute());
        }
        String email = attr(attrs, ldap.getEmailAttribute());
        String displayName = attr(attrs, ldap.getDisplayNameAttribute());
        List<String> groups = groups(attrs, ldap.getGroupAttribute());
        return new LdapUserInfo(dn, username, email, displayName, groups);
    }

    private String attr(Attributes attrs, String name) throws NamingException {
        if (name == null || name.isBlank() || attrs == null) {
            return null;
        }
        Attribute a = attrs.get(name);
        return a == null ? null : a.get() == null ? null : String.valueOf(a.get());
    }

    /** memberOf 多值属性 → 提取 CN 组名列表（如 "CN=devs,OU=groups,..." → devs）；属性名大小写不敏感 */
    private List<String> groups(Attributes attrs, String groupAttribute) throws NamingException {
        List<String> result = new ArrayList<>();
        if (groupAttribute == null || groupAttribute.isBlank() || attrs == null) {
            return result;
        }
        // 遍历属性名忽略大小写匹配（JNDI 返回属性名大小写可能不规范化）
        Attribute a = null;
        NamingEnumeration<String> ids = attrs.getIDs();
        while (ids.hasMore()) {
            String id = ids.next();
            if (groupAttribute.equalsIgnoreCase(id)) {
                a = attrs.get(id);
                break;
            }
        }
        if (a == null) {
            return result;
        }
        NamingEnumeration<?> values = a.getAll();
        while (values.hasMore()) {
            String dn = String.valueOf(values.next());
            result.add(extractCn(dn));
        }
        return result;
    }

    private String extractCn(String dn) {
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.regionMatches(true, 0, "cn=", 0, 3)) {
                return t.substring(3);
            }
        }
        return dn;
    }

    private static String nvl(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    /** 从 DN 首段 RDN 提取属性值（如 uid=zhangsan,ou=people → zhangsan） */
    private String firstRdn(String dn, String attribute) {
        String first = dn.split(",", 2)[0];
        int idx = first.indexOf('=');
        if (idx >= 0) {
            return first.substring(idx + 1);
        }
        return dn;
    }

    private SsoProperties.Ldap requireLdap() {
        SsoProperties props = ssoConfigService.getSsoProperties();
        if (!props.isEnabled() || props.getLdap() == null || !props.getLdap().isEnabled()) {
            throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
        }
        return props.getLdap();
    }

    public record LdapUserInfo(String dn, String username, String email, String displayName, List<String> groups) {}
}
