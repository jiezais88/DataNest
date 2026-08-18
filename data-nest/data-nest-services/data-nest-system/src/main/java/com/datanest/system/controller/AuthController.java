package com.datanest.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.exception.BusinessException;
import com.datanest.common.exception.ErrorCode;
import com.datanest.common.model.LoginRequest;
import com.datanest.common.model.Result;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.config.SsoProperties;
import com.datanest.system.dto.ProfileUpdateRequest;
import com.datanest.system.dto.UserProfileDTO;
import com.datanest.system.service.AuthSessionService;
import com.datanest.system.service.SsoConfigService;
import com.datanest.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "认证", description = "用户登录/登出")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final SsoConfigService ssoConfigService;

    public AuthController(UserService userService,
                          AuthSessionService authSessionService,
                          SsoConfigService ssoConfigService) {
        this.userService = userService;
        this.authSessionService = authSessionService;
        this.ssoConfigService = ssoConfigService;
    }

    @Operation(summary = "用户登录", description = "校验用户名密码（含失败锁定/密码过期/复杂度），返回 sa-token 令牌与用户信息")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        // 仅企业身份登录模式（sso-only）：仅放行 admin 本地登录（逃生通道，PRD R2）
        SsoProperties props = ssoConfigService.getSsoProperties();
        if (props.isEnabled() && props.isSsoOnlyMode() && !"admin".equals(req.username())) {
            throw new BusinessException(ErrorCode.SSO_ONLY_MODE);
        }
        UserService.LocalLoginResult user = userService.authenticateLocal(req.username(), req.password());
        return Result.ok(authSessionService.createSession(
                user.userId(), user.username(), user.roles(), user.permissions(),
                req.rememberMe() == Boolean.TRUE, user.mustChangePwd()));
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok(null);
    }

    @Operation(summary = "当前登录用户最新信息", description = "PM-14：返回最新 roles/permissions，前端进入应用/刷新权限快照用，无需重新登录")
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        UserLoginDTO user = userService.getCurrentUserInfo();
        return Result.ok(Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "roles", user.roles(),
                "permissions", user.permissions()
        ));
    }

    @Operation(summary = "当前登录用户完整资料", description = "个人中心：返回当前用户的邮箱/手机号/角色/创建时间等完整身份信息")
    @GetMapping("/profile")
    public Result<UserProfileDTO> profile() {
        return Result.ok(userService.getCurrentUserProfile());
    }

    @Operation(summary = "更新当前用户资料", description = "个人中心：仅可修改自己的邮箱/手机号；字段为 null 不修改，空字符串表示清空")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest req) {
        userService.updateCurrentUserProfile(req);
        return Result.ok(null);
    }
}
