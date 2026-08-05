package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DagProjectMapper extends BaseMapper<DagProject> {

    @Select("SELECT * FROM dag_project WHERE name = #{name} LIMIT 1")
    DagProject selectByName(@Param("name") String name);
}
