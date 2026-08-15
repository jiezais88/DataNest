package com.datanest.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.system.entity.DataPermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DataPermissionMapper extends BaseMapper<DataPermission> {

    /** 删除角色全部数据权限记录（保存时全量重建） */
    @Delete("DELETE FROM sys_data_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);
}
