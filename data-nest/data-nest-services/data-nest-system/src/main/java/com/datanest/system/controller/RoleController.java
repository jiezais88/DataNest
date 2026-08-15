package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.audit.AuditLog;
import com.datanest.common.audit.AuditOpType;
import com.datanest.common.audit.AuditResourceType;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.system.dto.RoleCreateRequest;
import com.datanest.system.dto.RoleUpdateRequest;
import com.datanest.system.dto.RoleUsersRequest;
import com.datanest.system.dto.RoleVO;
import com.datanest.system.dto.UserOptionDTO;
import com.datanest.system.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理（Sprint 11 F2）。
 * <p>
 * 预置 4 角色只读不可删；自定义角色支持创建/编辑/删除 + 功能权限点勾选。
 * 系统管理类能力，仅超级管理员（权限点 role:*）。
 */
@Tag(name = "角色管理", description = "角色 CRUD（预置只读，自定义可增删改）")
@RestController
@RequestMapping("/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "角色列表（预置 + 自定义，含功能权限点）")
    @SaCheckPermission(PermissionCode.ROLE_VIEW)
    @GetMapping
    public Result<List<RoleVO>> list() {
        return Result.ok(roleService.listRoles());
    }

    @Operation(summary = "创建自定义角色")
    @SaCheckPermission(PermissionCode.ROLE_CREATE)
    @AuditLog(resourceType = AuditResourceType.ROLE, opType = AuditOpType.CREATE,
            resourceId = "#result.data.id", resourceName = "#req.name")
    @PostMapping
    public Result<RoleVO> create(@Valid @RequestBody RoleCreateRequest req) {
        return Result.ok(roleService.createRole(req));
    }

    @Operation(summary = "编辑自定义角色（描述 + 功能权限）")
    @SaCheckPermission(PermissionCode.ROLE_UPDATE)
    @AuditLog(resourceType = AuditResourceType.ROLE, opType = AuditOpType.UPDATE,
            resourceId = "#id", resourceName = "#result.data.name")
    @PutMapping("/{id}")
    public Result<RoleVO> update(@Parameter(description = "角色 ID") @PathVariable Long id,
                                 @Valid @RequestBody RoleUpdateRequest req) {
        return Result.ok(roleService.updateRole(id, req));
    }

    @Operation(summary = "删除自定义角色")
    @SaCheckPermission(PermissionCode.ROLE_DELETE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "角色 ID") @PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.ok(null);
    }

    @Operation(summary = "查询角色成员", description = "权限配置页成员 Tab 已选列表")
    @SaCheckPermission(PermissionCode.ROLE_VIEW)
    @GetMapping("/{id}/users")
    public Result<List<UserOptionDTO>> listUsers(@Parameter(description = "角色 ID") @PathVariable Long id) {
        return Result.ok(roleService.listRoleUsers(id));
    }

    @Operation(summary = "设置角色成员", description = "全量替换该角色的用户关联，不影响用户其他角色")
    @SaCheckPermission(PermissionCode.ROLE_UPDATE)
    @PutMapping("/{id}/users")
    public Result<Void> setUsers(@Parameter(description = "角色 ID") @PathVariable Long id,
                                 @Valid @RequestBody RoleUsersRequest req) {
        roleService.setRoleUsers(id, req.userIds());
        return Result.ok(null);
    }
}
