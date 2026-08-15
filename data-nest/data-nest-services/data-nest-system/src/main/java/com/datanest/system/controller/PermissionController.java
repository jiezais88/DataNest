package com.datanest.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.datanest.common.auth.PermissionCode;
import com.datanest.common.model.Result;
import com.datanest.system.dto.PermissionVO;
import com.datanest.system.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点清单（Sprint 11 F2）。
 * <p>
 * 供角色管理页功能权限勾选树；属角色管理查看能力（role:view）。
 */
@Tag(name = "权限点", description = "按钮级权限点清单（供角色勾选）")
@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Operation(summary = "权限点清单", description = "返回全部按钮级权限点（模块:动作），供角色勾选树分组")
    @SaCheckPermission(PermissionCode.ROLE_VIEW)
    @GetMapping
    public Result<List<PermissionVO>> list() {
        return Result.ok(permissionService.listPermissions());
    }
}
