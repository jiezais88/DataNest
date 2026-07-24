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
}
