package com.datanest.system.service;

import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.system.config.SsoProperties;
import com.datanest.system.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LDAP 用户同步（Sprint 14 F5）。
 * <p>
 * 管理员手动触发：拉取目录全部用户，逐条走 {@link UserService#resolveSsoUser}
 * 自动建号/自动绑定 + 角色映射。不删除平台账号（仅新增/更新）。
 */
@Service
public class SsoSyncService {

    private final SsoConfigService ssoConfigService;
    private final LdapClientService ldapClientService;
    private final UserService userService;

    public SsoSyncService(SsoConfigService ssoConfigService,
                          LdapClientService ldapClientService,
                          UserService userService) {
        this.ssoConfigService = ssoConfigService;
        this.ldapClientService = ldapClientService;
        this.userService = userService;
    }

    @Transactional
    public SyncResult syncUsers() {
        SsoProperties props = ssoConfigService.getSsoProperties();
        if (!props.isEnabled() || props.getLdap() == null || !props.getLdap().isEnabled()) {
            throw new BusinessException(ErrorCode.SSO_NOT_CONFIGURED);
        }
        List<LdapClientService.LdapUserInfo> users = ldapClientService.searchAllUsers();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (LdapClientService.LdapUserInfo info : users) {
            if (info.username() == null || info.username().isBlank()) {
                skipped++;
                continue;
            }
            boolean exists = userService.existsBySsoSubject(info.dn());
            User u = userService.resolveSsoUser("LDAP", info.dn(), info.email(), info.username(), info.groups());
            if (u != null && exists) {
                updated++;
            } else {
                created++;
            }
        }
        return new SyncResult(users.size(), created, updated, skipped);
    }

    public record SyncResult(int total, int created, int updated, int skipped) {
    }
}
