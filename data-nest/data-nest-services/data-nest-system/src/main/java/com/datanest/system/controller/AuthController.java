package com.datanest.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.model.LoginRequest;
import com.datanest.common.model.Result;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "认证", description = "用户登录/登出")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "用户登录", description = "校验用户名密码，返回 sa-token 令牌与用户信息")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        UserLoginDTO user = userService.verify(req.username(), req.password());

        if (req.rememberMe() == Boolean.TRUE) {
            // 记住我：独立 deviceType，与普通登录（默认 "pc"）互不复用、互不覆盖豁免；
            // token 绝对永不过期 + 该 token 永不因无操作冻结
            StpUtil.login(user.userId(), StpUtil.createSaLoginParameter()
                    .setIsLastingCookie(true)
                    .setDeviceType("remember")
                    .setTimeout(-1)          // SaTokenDao.NEVER_EXPIRE，绝对永不过期
                    .setActiveTimeout(-1));  // 该 token 永不冻结
        } else {
            // 非记住我：维持全局配置（7 天绝对 + 30 分钟无操作踢出）
            StpUtil.login(user.userId());
        }
        StpUtil.getSession().set("roles", user.roles());
        // Sprint 11 F2：权限点集合写入 Session，供 @SaCheckPermission 跨服务校验
        StpUtil.getSession().set("permissions", user.permissions());
        // 审计切面从 session 读取操作人用户名（避免各服务回查 system 库）
        StpUtil.getSession().set("username", user.username());

        Map<String, Object> result = new HashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("userInfo", Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "roles", user.roles(),
                "permissions", user.permissions()
        ));
        return Result.ok(result);
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
}
