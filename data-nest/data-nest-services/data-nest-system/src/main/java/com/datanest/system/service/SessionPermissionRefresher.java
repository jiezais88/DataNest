package com.datanest.system.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.datanest.system.entity.User;
import com.datanest.system.mapper.PermissionMapper;
import com.datanest.system.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 已登录用户权限快照刷新（Sprint 11 F2 / PM-14）。
 * <p>
 * sa-token 共享 Redis Session（is-share=true），登录时把 roles/permissions 快照写入 Session，
 * 供各服务 @SaCheckRole / @SaCheckPermission 跨服务校验。角色功能权限/成员/用户角色变更后，
 * 必须主动刷新受影响已登录用户的 Session 快照，才能实现 PRD「保存即时生效，下次请求即按新权限校验」，
 * 无需用户重新登录。未登录用户（无 Session）跳过，fail-open。
 */
@Service
public class SessionPermissionRefresher {

    private static final Logger log = LoggerFactory.getLogger(SessionPermissionRefresher.class);

    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;

    public SessionPermissionRefresher(UserMapper userMapper, PermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.permissionMapper = permissionMapper;
    }

    /** 刷新单个用户 Session 中的 roles/permissions（用户未登录则跳过） */
    public void refreshUser(Long userId) {
        try {
            SaSession session = StpUtil.getSessionByLoginId(userId, false);
            if (session == null) {
                return; // 用户未登录，无快照需刷新
            }
            session.set("roles", userMapper.selectRoleCodesByUserId(userId));
            session.set("permissions", permissionMapper.selectCodesByUserId(userId));
        } catch (Exception e) {
            // fail-open：权限刷新失败不影响主链路（用户下次登录自然拿到最新权限）
            log.warn("刷新用户权限快照失败: userId={}", userId, e);
        }
    }

    /** 刷新某角色下全部用户的权限快照（角色权限点变更后调用） */
    public void refreshRoleUsers(Long roleId) {
        try {
            List<User> users = userMapper.selectUsersByRoleId(roleId);
            for (User u : users) {
                refreshUser(u.getId());
            }
        } catch (Exception e) {
            log.warn("刷新角色用户权限快照失败: roleId={}", roleId, e);
        }
    }
}
