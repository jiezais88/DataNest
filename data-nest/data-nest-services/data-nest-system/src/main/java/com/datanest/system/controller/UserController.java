package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.datanest.common.audit.AuditLog;
import com.datanest.common.audit.AuditLogEvent;
import com.datanest.common.audit.AuditLogRecorder;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.model.PageResult;
import com.datanest.common.model.Result;
import com.datanest.system.dto.*;
import com.datanest.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户管理", description = "用户 CRUD / 启停 / 密码管理")
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final AuditLogRecorder auditLogRecorder;

    public UserController(UserService userService, AuditLogRecorder auditLogRecorder) {
        this.userService = userService;
        this.auditLogRecorder = auditLogRecorder;
    }

    @Operation(summary = "用户分页列表（仅超管）")
    @SaCheckPermission(PermissionCode.USER_VIEW)
    @GetMapping
    public Result<PageResult<UserVO>> list(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int pageSize,
            @Parameter(description = "用户名/姓名关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "角色编码") @RequestParam(required = false) String roleCode,
            @Parameter(description = "状态（ENABLED/DISABLED）") @RequestParam(required = false) String status) {
        return Result.ok(userService.listUsers(page, pageSize, keyword, roleCode, status));
    }

    @Operation(summary = "创建用户（仅超管）")
    @SaCheckPermission(PermissionCode.USER_CREATE)
    @AuditLog(resourceType = AuditResourceType.USER, opType = AuditOpType.CREATE,
            resourceId = "#result.data.id", resourceName = "#req.username")
    @PostMapping
    public Result<UserVO> create(@Valid @RequestBody UserCreateRequest req) {
        return Result.ok(userService.createUser(req));
    }

    @Operation(summary = "编辑用户（仅超管）")
    @SaCheckPermission(PermissionCode.USER_UPDATE)
    @AuditLog(resourceType = AuditResourceType.USER, opType = AuditOpType.UPDATE,
            resourceId = "#userId", resourceName = "#result.data.username")
    @PutMapping("/{userId}")
    public Result<UserVO> update(@Parameter(description = "用户 ID") @PathVariable Long userId,
                                 @Valid @RequestBody UserUpdateRequest req) {
        return Result.ok(userService.updateUser(userId, req));
    }

    @Operation(summary = "切换启用/禁用（仅超管）")
    @SaCheckPermission(PermissionCode.USER_TOGGLE)
    @PutMapping("/{userId}/toggle")
    public Result<Void> toggleStatus(@Parameter(description = "用户 ID") @PathVariable Long userId) {
        UserVO vo = userService.toggleStatus(userId);
        // opType 按结果动态区分 ENABLE/DISABLE（注解 opType 是常量无法动态，故手动埋点）
        writeToggleAudit(userId, vo);
        return Result.ok(null);
    }

    /** 启停切换审计：按新状态记 ENABLE/DISABLE（fail-open，对齐 writeRoleAudit 模式） */
    private void writeToggleAudit(Long userId, UserVO vo) {
        try {
            auditLogRecorder.record(new AuditLogEvent(
                    StpUtil.getLoginIdAsLong(), null,
                    (Boolean.TRUE.equals(vo.enabled()) ? AuditOpType.ENABLE : AuditOpType.DISABLE).name(),
                    AuditResourceType.USER.name(),
                    String.valueOf(userId), vo.username(),
                    null, AuditLogEvent.RESULT_SUCCESS, null, null));
        } catch (Exception e) {
            // fail-open：审计失败不影响启停主链路
        }
    }

    @Operation(summary = "修改密码（用户自主）")
    @SaCheckLogin
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        long userId = StpUtil.getLoginIdAsLong();
        userService.changePassword(userId, req.oldPassword(), req.newPassword());
        return Result.ok(null);
    }

    @Operation(summary = "管理员重置密码（仅超管）")
    @SaCheckPermission(PermissionCode.USER_RESET_PWD)
    @AuditLog(resourceType = AuditResourceType.USER, opType = AuditOpType.RESET_PASSWORD, resourceId = "#userId")
    @PutMapping("/{userId}/reset-password")
    public Result<Void> resetPassword(@Parameter(description = "用户 ID") @PathVariable Long userId,
                                      @Valid @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(userId, req.newPassword());
        return Result.ok(null);
    }
}
