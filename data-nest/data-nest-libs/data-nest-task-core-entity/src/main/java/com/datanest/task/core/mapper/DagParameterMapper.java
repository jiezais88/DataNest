package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagParameter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagParameterMapper extends BaseMapper<DagParameter> {

    @Select("SELECT * FROM dag_parameter WHERE dag_id = #{dagId} ORDER BY id ASC")
    List<DagParameter> selectByDagId(@Param("dagId") Long dagId);
}
