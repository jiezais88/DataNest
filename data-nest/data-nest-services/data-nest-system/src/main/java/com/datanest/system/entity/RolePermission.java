package com.datanest.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限点关联实体（Sprint 11 F2，sys_role_permission 表 Sprint 0 已建、此前为空）。
 */
@Data
@TableName("sys_role_permission")
public class RolePermission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long roleId;
    private Long permissionId;
    private LocalDateTime createdAt;
}
