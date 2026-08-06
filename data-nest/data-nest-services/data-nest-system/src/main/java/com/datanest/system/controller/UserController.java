package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.system.dto.*;
import com.datanest.system.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户列表（仅超级管理员）
     */
    @SaCheckRole("SUPER_ADMIN")
    @GetMapping
    public Result<PageResult<UserVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String status) {
        return Result.ok(userService.listUsers(page, pageSize, keyword, roleCode, status));
    }

    /**
     * 创建用户 (仅超级管理员)
     */
    @SaCheckRole("SUPER_ADMIN")
    @PostMapping
    public Result<UserVO> create(@Valid @RequestBody UserCreateRequest req) {
        return Result.ok(userService.createUser(req));
    }

    /**
     * 编辑用户 (仅超级管理员)
     */
    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/{userId}")
    public Result<UserVO> update(@PathVariable Long userId,
                                 @Valid @RequestBody UserUpdateRequest req) {
        return Result.ok(userService.updateUser(userId, req));
    }

    /**
     * 切换启用/禁用 (仅超级管理员)
     */
    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/{userId}/toggle")
    public Result<Void> toggleStatus(@PathVariable Long userId) {
        userService.toggleStatus(userId);
        return Result.ok(null);
    }

    /**
     * 修改密码 (用户自主)
     */
    @SaCheckLogin
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        long userId = StpUtil.getLoginIdAsLong();
        userService.changePassword(userId, req.oldPassword(), req.newPassword());
        return Result.ok(null);
    }

    /**
     * 管理员重置密码 (仅超级管理员)
     */
    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/{userId}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long userId,
                                      @Valid @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(userId, req.newPassword());
        return Result.ok(null);
    }
}
