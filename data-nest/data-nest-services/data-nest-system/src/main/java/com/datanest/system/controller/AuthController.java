package com.datanest.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.model.LoginRequest;
import com.datanest.common.model.Result;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

        StpUtil.login(user.userId(), req.rememberMe() == Boolean.TRUE);
        StpUtil.getSession().set("roles", user.roles());

        Map<String, Object> result = new HashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("userInfo", Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "roles", user.roles()
        ));
        return Result.ok(result);
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok(null);
    }
}
