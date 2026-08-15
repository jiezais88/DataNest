package com.datanest.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.system.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查询用户全部角色权限点 code 并集（Sprint 11 F2）。
     * <p>
     * 登录时写入 Session 供 @SaCheckPermission 跨服务校验；internal 端点查询也复用。
     */
    @Select("""
                SELECT DISTINCT p.code FROM sys_permission p
                INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
                INNER JOIN sys_user_role ur ON rp.role_id = ur.role_id
                WHERE ur.user_id = #{userId}
                ORDER BY p.code
            """)
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
