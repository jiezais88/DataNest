package com.datanest.system.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.system.config.SsoProperties;
import com.datanest.system.entity.User;
import com.datanest.system.mapper.PermissionMapper;
import com.datanest.system.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SSO 登录主流程（Sprint 14）。
 * <p>
 * OIDC / LDAP 认证通过后统一走 {@link UserService#resolveSsoUser} 完成
 * 「匹配 → 自动绑定 → 自动建号」三分支（PRD R2），随后建立 sa-token 会话。
 */
@Service
public class SsoAuthService {

    private final SsoConfigService ssoConfigService;
    private final OidcClientService oidcClientService;
    private final LdapClientService ldapClientService;
    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;

    public SsoAuthService(SsoConfigService ssoConfigService,
                          OidcClientService oidcClientService,
                          LdapClientService ldapClientService,
                          UserService userService,
                          AuthSessionService authSessionService,
                          UserMapper userMapper,
                          PermissionMapper permissionMapper) {
        this.ssoConfigService = ssoConfigService;
        this.oidcClientService = oidcClientService;
        this.ldapClientService = ldapClientService;
        this.userService = userService;
        this.authSessionService = authSessionService;
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
    }

    /** OIDC 授权码登录 */
    public Map<String, Object> loginByOidc(String code, String state) {
        OidcClientService.OidcUserInfo info = oidcClientService.authenticate(code, state);
        return doLogin("OIDC", info.subject(), info.email(), info.username(), info.groups());
    }

    /** LDAP 域账号登录 */
    public Map<String, Object> loginByLdap(String username, String password) {
        LdapClientService.LdapUserInfo info = ldapClientService.authenticate(username, password);
        return doLogin("LDAP", info.dn(), info.email(), info.username(), info.groups());
    }

    /** 登录前统一检查 SSO 总开关（controller 也兜底调用） */
    public SsoProperties requireEnabled() {
        SsoProperties props = ssoConfigService.getSsoProperties();
        if (!props.isEnabled()) {
            throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
        }
        return props;
    }

    private Map<String, Object> doLogin(String source, String subject, String email, String username,
                                        List<String> groups) {
        User user = userService.resolveSsoUser(source, subject, email, username, groups);
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = permissionMapper.selectCodesByUserId(user.getId());
        // SSO 用户无本地密码，无强制改密语义
        return authSessionService.createSession(user.getId(), user.getUsername(),
                roles, permissions, false, false);
    }
}
