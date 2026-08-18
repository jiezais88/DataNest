package com.datanest.system.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录会话写入（Sprint 14 抽取）。
 * <p>
 * 本地密码登录 / OIDC / LDAP 登录共用：建立 sa-token 会话，写入
 * roles / permissions / username 快照（供各服务 @SaCheckRole/@SaCheckPermission 跨服务校验），
 * 返回 token + userInfo。
 */
@Service
public class AuthSessionService {

    public Map<String, Object> createSession(Long userId, String username,
                                             List<String> roles, List<String> permissions,
                                             boolean rememberMe, boolean mustChangePwd) {
        if (rememberMe) {
            // 记住我：独立 deviceType，与普通登录（默认 "pc"）互不复用、互不覆盖豁免；
            // token 绝对永不过期 + 该 token 永不因无操作冻结
            StpUtil.login(userId, StpUtil.createSaLoginParameter()
                    .setIsLastingCookie(true)
                    .setDeviceType("remember")
                    .setTimeout(-1)          // SaTokenDao.NEVER_EXPIRE，绝对永不过期
                    .setActiveTimeout(-1));  // 该 token 永不冻结
        } else {
            // 非记住我：维持全局配置（7 天绝对 + 30 分钟无操作踢出）
            StpUtil.login(userId);
        }
        SaSession session = StpUtil.getSession();
        session.set("roles", roles);
        // Sprint 11 F2：权限点集合写入 Session，供 @SaCheckPermission 跨服务校验
        session.set("permissions", permissions);
        // 审计切面从 session 读取操作人用户名（避免各服务回查 system 库）
        session.set("username", username);

        Map<String, Object> result = new HashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("userInfo", Map.of(
                "userId", userId,
                "username", username,
                "roles", roles,
                "permissions", permissions,
                "mustChangePwd", mustChangePwd
        ));
        return result;
    }
}
