package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按 id 批量查询用户，用于列表页 createdBy/updatedBy 映射用户名。
     */
    @Select("<script>" +
            "SELECT id, username FROM sys_user WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<SysUser> selectByIdList(@Param("ids") Collection<Long> ids);

    /**
     * Sprint 5：按 id 批量查询邮箱，用于告警收件人反查。
     * 仅返回已填写邮箱的用户。
     */
    @Select("<script>" +
            "SELECT id, username, email FROM sys_user WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " AND email IS NOT NULL AND email != ''" +
            "</script>")
    List<SysUser> selectEmailsByIds(@Param("ids") Collection<Long> ids);

    /**
     * Sprint 7 F1：按用户名模糊查询 userId 列表，用于资产搜索的「负责人」维度匹配。
     * LIMIT 防止常见词关键词导致全表返回。
     */
    @Select("SELECT id FROM sys_user WHERE username LIKE CONCAT('%', #{keyword}, '%') LIMIT 50")
    List<Long> selectIdsByUsernameKeyword(@Param("keyword") String keyword);
}
