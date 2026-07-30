package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagEdge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagEdgeMapper extends BaseMapper<DagEdge> {

    @Select("SELECT * FROM dag_edge WHERE dag_id = #{dagId} ORDER BY id ASC")
    List<DagEdge> selectByDagId(@Param("dagId") Long dagId);
}
