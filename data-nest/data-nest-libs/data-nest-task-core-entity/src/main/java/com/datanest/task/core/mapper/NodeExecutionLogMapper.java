package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.NodeExecutionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NodeExecutionLogMapper extends BaseMapper<NodeExecutionLog> {

    @Select("SELECT * FROM node_execution_log WHERE execution_id = #{executionId} AND node_id = #{nodeId} ORDER BY line_num ASC, id ASC")
    List<NodeExecutionLog> selectByExecutionAndNode(@Param("executionId") Long executionId,
                                                    @Param("nodeId") String nodeId);
}
