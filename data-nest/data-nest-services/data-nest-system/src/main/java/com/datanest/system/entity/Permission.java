package com.datanest.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统权限点实体（Sprint 11 F2，sys_permission 表 Sprint 0 已建、此前为空）。
 * <p>
 * code 规范「模块:动作」，常量见 common {@code PermissionCode}。
 */
@Data
@TableName("sys_permission")
public class Permission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String code;
    private String name;
    private String description;
    private LocalDateTime createdAt;
}
