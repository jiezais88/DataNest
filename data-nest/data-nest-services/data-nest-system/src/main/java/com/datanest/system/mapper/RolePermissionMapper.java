package com.datanest.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.system.entity.RolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /** 查询角色已关联的权限点 code 列表（角色详情/编辑回显） */
    @Select("""
                SELECT p.code FROM sys_permission p
                INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
                WHERE rp.role_id = #{roleId}
                ORDER BY p.code
            """)
    List<String> selectCodesByRoleId(@Param("roleId") Long roleId);

    /** 删除角色全部权限点关联（保存时全量重建） */
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
