package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.NodeExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NodeExecutionMapper extends BaseMapper<NodeExecution> {

    @Select("SELECT * FROM node_execution WHERE execution_id = #{executionId} ORDER BY id ASC")
    List<NodeExecution> selectByExecutionId(@Param("executionId") Long executionId);

    @Select("SELECT * FROM node_execution WHERE ds_task_instance_id = #{dsTaskInstanceId} LIMIT 1")
    NodeExecution selectByDsTaskInstanceId(@Param("dsTaskInstanceId") Long dsTaskInstanceId);

    /**
     * Sprint 3 P1-2：按 syncJobId 查 SYNC 节点执行（最新一条）
     * 用于 DagExecutionSyncService 反查 sync_job_history 同步终态
     */
    @Select("SELECT * FROM node_execution WHERE sync_job_id = #{syncJobId} AND status IN ('RUNNING', 'WAITING') ORDER BY id DESC LIMIT 1")
    NodeExecution selectRunningBySyncJobId(@Param("syncJobId") Long syncJobId);

    /**
     * Sprint 3 P1-1：DAG 被 stop 时把未结束子节点标 SKIPPED
     * 乐观锁：只 update 当前 status 为 WAITING/RUNNING 的行
     */
    @Update("UPDATE node_execution SET status = 'SKIPPED', end_time = #{endTime} " +
            "WHERE execution_id = #{executionId} AND status IN ('WAITING', 'RUNNING')")
    int markSkippedByExecutionId(@Param("executionId") Long executionId, @Param("endTime") LocalDateTime endTime);
}
