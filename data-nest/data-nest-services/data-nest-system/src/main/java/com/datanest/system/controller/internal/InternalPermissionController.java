package com.datanest.system.controller.internal;

import com.datanest.common.model.Result;
import com.datanest.common.model.UserDataPermissionDTO;
import com.datanest.system.service.DataPermissionService;
import com.datanest.system.service.PermissionService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限查询内部接口（实现 system-api 的 SystemPermissionApi 契约，Sprint 11 F2）。
 * <p>
 * 仅供服务间内部调用：engineering/governance/data-service 经 Feign 查询用户权限点集合与数据权限范围；
 * 由 common 的 InternalTokenFilter 做内部令牌鉴权。
 */
@Hidden
@RestController
@RequestMapping("/internal")
public class InternalPermissionController {

    private final PermissionService permissionService;
    private final DataPermissionService dataPermissionService;

    public InternalPermissionController(PermissionService permissionService,
                                        DataPermissionService dataPermissionService) {
        this.permissionService = permissionService;
        this.dataPermissionService = dataPermissionService;
    }

    /** 查询用户全部角色的权限点 code 并集 */
    @GetMapping("/permissions/{userId}")
    public Result<List<String>> permissions(@PathVariable Long userId) {
        return Result.ok(permissionService.getPermissionCodesByUserId(userId));
    }

    /** 查询用户全部角色合并后的数据权限范围（三级白名单） */
    @GetMapping("/data-permission/{userId}")
    public Result<UserDataPermissionDTO> dataPermission(@PathVariable Long userId) {
        return Result.ok(dataPermissionService.getUserDataPermission(userId));
    }
}
