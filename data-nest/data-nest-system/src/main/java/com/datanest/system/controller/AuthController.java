package com.datanest.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.model.LoginRequest;
import com.datanest.common.model.Result;
import com.datanest.common.model.UserLoginDTO;
import com.datanest.system.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户登录
     * POST /api/system/auth/login
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        UserLoginDTO user = userService.verify(req.username(), req.password());

        StpUtil.login(user.userId(), req.rememberMe());

        Map<String, Object> result = new HashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("userInfo", Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "roles", user.roles()
        ));
        return Result.ok(result);
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok(null);
    }
}
