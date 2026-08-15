package com.datanest.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datanest.system.dto.PermissionVO;
import com.datanest.system.entity.Permission;
import com.datanest.system.mapper.PermissionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限点服务（Sprint 11 F2）。
 * <p>
 * 权限点由 Flyway 种子脚本固化，本服务仅提供只读查询（清单 + 用户权限点并集）。
 */
@Service
public class PermissionService {

    private final PermissionMapper permissionMapper;

    public PermissionService(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    /** 全部权限点清单（供角色管理页勾选树） */
    public List<PermissionVO> listPermissions() {
        List<Permission> permissions = permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getCode));
        return permissions.stream()
                .map(p -> new PermissionVO(p.getId(), p.getCode(), p.getName(), p.getDescription()))
                .toList();
    }

    /** 用户全部角色的权限点 code 并集（登录时写入 Session + internal 端点复用） */
    public List<String> getPermissionCodesByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return permissionMapper.selectCodesByUserId(userId);
    }
}
