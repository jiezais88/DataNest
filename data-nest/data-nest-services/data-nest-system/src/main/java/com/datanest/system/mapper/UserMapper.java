package com.datanest.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.system.entity.User;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper extends BaseMapper<User> {

    @Select("""
                <script>
                SELECT DISTINCT u.* FROM sys_user u
                LEFT JOIN sys_user_role ur ON u.id = ur.user_id
                LEFT JOIN sys_role r ON ur.role_id = r.id
                <where>
                    <if test='keyword != null and keyword != \"\"'>
                        AND (u.username ILIKE CONCAT('%', #{keyword}, '%')
                             OR u.email ILIKE CONCAT('%', #{keyword}, '%'))
                    </if>
                    <if test='roleCode != null and roleCode != \"\"'>
                        AND r.code = #{roleCode}
                    </if>
                    <if test='enabled != null'>
                        AND u.enabled = #{enabled}
                    </if>
                </where>
                ORDER BY u.created_at DESC
                </script>
            """)
    IPage<User> selectUserPage(Page<User> page,
                               @Param("keyword") String keyword,
                               @Param("roleCode") String roleCode,
                               @Param("enabled") Boolean enabled);

    @Select("""
                SELECT r.code FROM sys_role r
                INNER JOIN sys_user_role ur ON r.id = ur.role_id
                WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    void deleteUserRoles(@Param("userId") Long userId);

    @Insert("INSERT INTO sys_user_role (id, user_id, role_id) VALUES (#{id}, #{userId}, #{roleId})")
    void insertUserRole(@Param("id") Long id, @Param("userId") Long userId, @Param("roleId") Long roleId);

    /**
     * 查询用户绑定的角色 ID 列表（Sprint 11 F2，数据权限/权限点聚合用）。
     */
    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 查询绑定某角色的用户数（Sprint 11 F2，删除自定义角色前置校验）。
     */
    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_id = #{roleId}")
    Long selectUserCountByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询绑定某角色的用户列表（Sprint 11 F2，权限配置页成员 Tab）。
     */
    @Select("""
                SELECT u.* FROM sys_user u
                INNER JOIN sys_user_role ur ON u.id = ur.user_id
                WHERE ur.role_id = #{roleId}
                ORDER BY u.username ASC
            """)
    List<User> selectUsersByRoleId(@Param("roleId") Long roleId);

    /**
     * 删除某角色的全部用户关联（Sprint 11 F2，成员 Tab 全量替换）。
     */
    @Delete("DELETE FROM sys_user_role WHERE role_id = #{roleId}")
    void deleteUserRolesByRoleId(@Param("roleId") Long roleId);

    /**
     * Sprint 5：查询已填写邮箱的用户（告警接收人选择器）。
     * 支持按用户名/邮箱模糊搜索。
     */
    @Select("""
                <script>
                SELECT id, username, email FROM sys_user
                WHERE email IS NOT NULL AND email &lt;&gt; ''
                <if test='keyword != null and keyword != ""'>
                    AND (username ILIKE CONCAT('%', #{keyword}, '%')
                         OR email ILIKE CONCAT('%', #{keyword}, '%'))
                </if>
                ORDER BY username ASC
                LIMIT 100
                </script>
            """)
    List<User> selectUsersWithEmail(@Param("keyword") String keyword);

    /**
     * Sprint 7 F1：全部启用用户的轻量选项（资产目录负责人选择器，不要求填邮箱）。
     * 支持按用户名/邮箱模糊搜索。
     */
    @Select("""
                <script>
                SELECT id, username, email FROM sys_user
                WHERE enabled = TRUE
                <if test='keyword != null and keyword != ""'>
                    AND (username ILIKE CONCAT('%', #{keyword}, '%')
                         OR email ILIKE CONCAT('%', #{keyword}, '%'))
                </if>
                ORDER BY username ASC
                LIMIT 100
                </script>
            """)
    List<User> selectEnabledUserOptions(@Param("keyword") String keyword);
}
