package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.ExecutionQueue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExecutionQueueMapper extends BaseMapper<ExecutionQueue> {

    /** 队列名唯一性检查（排除自身 id） */
    @Select("SELECT COUNT(*) FROM execution_queue WHERE queue_name = #{queueName} AND id != #{excludeId}")
    long countByNameExcludeId(@Param("queueName") String queueName, @Param("excludeId") Long excludeId);

    /** 绑定某队列的 DAG 数（删除队列前置校验 QU-3） */
    @Select("SELECT COUNT(*) FROM dag WHERE queue_name = #{queueName}")
    long countDagsByQueue(@Param("queueName") String queueName);
}
