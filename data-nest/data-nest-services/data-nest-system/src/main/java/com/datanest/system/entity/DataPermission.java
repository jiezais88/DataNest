package com.datanest.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色数据权限白名单实体（Sprint 11 F2，sys_data_permission 表）。
 * <p>
 * 语义：角色无任何记录 = 全量可见（默认，向后兼容）；有记录 = 白名单过滤，最细粒度优先匹配。
 * {@code databaseName}/{@code tableName} 可空，空 = 库级/表级通配。
 */
@Data
@TableName("sys_data_permission")
public class DataPermission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long roleId;
    private Long datasourceId;
    private String databaseName;
    private String tableName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
