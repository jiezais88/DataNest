package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagExecutionMapper extends BaseMapper<DagExecution> {

    @Select("SELECT * FROM dag_execution WHERE dag_id = #{dagId} ORDER BY start_time DESC")
    List<DagExecution> selectByDagId(@Param("dagId") Long dagId);

    @Select("SELECT * FROM dag_execution WHERE ds_process_instance_id = #{dsProcessInstanceId} LIMIT 1")
    DagExecution selectByDsProcessInstanceId(@Param("dsProcessInstanceId") Long dsProcessInstanceId);

    @Select("SELECT * FROM dag_execution WHERE status = 'RUNNING'")
    List<DagExecution> selectRunning();
}
