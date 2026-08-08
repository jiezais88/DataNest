package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.Dag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DagMapper extends BaseMapper<Dag> {

    @Select("SELECT COUNT(*) FROM dag WHERE project_id = #{projectId} AND name = #{name}")
    long countByProjectIdAndName(@Param("projectId") Long projectId, @Param("name") String name);
}
