package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.NodeExecution;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NodeExecutionMapper extends BaseMapper<NodeExecution> {

    @Select("SELECT * FROM node_execution WHERE execution_id = #{executionId} ORDER BY id ASC")
    List<NodeExecution> selectByExecutionId(@Param("executionId") Long executionId);

    @Select("SELECT * FROM node_execution WHERE ds_task_instance_id = #{dsTaskInstanceId} LIMIT 1")
    NodeExecution selectByDsTaskInstanceId(@Param("dsTaskInstanceId") Long dsTaskInstanceId);

    /**
     * Sprint 4：查询 RUNNING 且开始时间早于阈值的节点，用于超时告警扫描。
     */
    @Select("SELECT * FROM node_execution WHERE status = 'RUNNING' " +
            "AND start_time IS NOT NULL AND start_time < #{threshold} " +
            "ORDER BY start_time ASC LIMIT #{limit}")
    List<NodeExecution> selectTimeoutRunning(@Param("threshold") LocalDateTime threshold,
                                             @Param("limit") int limit);

    /**
     * Sprint 4 review：查询 RUNNING 节点并附带 dag_id，用于按 DAG 告警配置判断超时。
     */
    @Select("SELECT ne.*, de.dag_id AS dag_id FROM node_execution ne " +
            "JOIN dag_execution de ON ne.execution_id = de.id " +
            "WHERE ne.status = 'RUNNING' AND ne.start_time IS NOT NULL " +
            "ORDER BY ne.start_time ASC LIMIT #{limit}")
    List<NodeExecution> selectRunningWithDagId(@Param("limit") int limit);

    /**
     * Sprint 3 P1-2：按 syncJobId 查 SYNC 节点执行（最新一条）
     * 用于 DagExecutionSyncService 反查 sync_job_history 同步终态
     */
    @Select("SELECT * FROM node_execution WHERE sync_job_id = #{syncJobId} AND status IN ('RUNNING', 'WAITING') ORDER BY id DESC LIMIT 1")
    NodeExecution selectRunningBySyncJobId(@Param("syncJobId") Long syncJobId);

    /**
     * 微服务化 3.1 新增：按 syncJobId 查全部未结束（RUNNING/WAITING）节点执行，供内部 API 列表返回。
     */
    @Select("SELECT * FROM node_execution WHERE sync_job_id = #{syncJobId} AND status IN ('RUNNING', 'WAITING') ORDER BY id DESC")
    List<NodeExecution> selectRunningListBySyncJobId(@Param("syncJobId") Long syncJobId);

    /**
     * Sprint 3 P1-1：DAG 被 stop 时把未结束子节点标 SKIPPED
     * 乐观锁：只 update 当前 status 为 WAITING/RUNNING 的行
     */
    @Update("UPDATE node_execution SET status = 'SKIPPED', end_time = #{endTime} " +
            "WHERE execution_id = #{executionId} AND status IN ('WAITING', 'RUNNING')")
    int markSkippedByExecutionId(@Param("executionId") Long executionId, @Param("endTime") LocalDateTime endTime);

    /**
     * Sprint 3 性能优化：真正的批量插入
     */
    @Insert("<script>" +
            "INSERT INTO node_execution (id, execution_id, node_id, node_name, node_type, status, ds_task_instance_id, sync_job_id, start_time, end_time, duration_ms, error_message, output_info) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.executionId}, #{item.nodeId}, #{item.nodeName}, #{item.nodeType}, #{item.status}, #{item.dsTaskInstanceId}, #{item.syncJobId}, #{item.startTime}, #{item.endTime}, #{item.durationMs}, #{item.errorMessage}, #{item.outputInfo})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<NodeExecution> list);

    /**
     * Sprint 3 性能优化：批量更新 node_execution。
     * 使用 MySQL CASE WHEN 语法，避免 multi-statement 限制。
     * 注意：可能为 null 的参数必须显式 jdbcType，否则 Postgres 会把 CASE WHEN 表达式
     * 推断为 text，报 "column xxx is of type bigint but expression is of type text"。
     * 仅 jdbcType 仍不够：当某列的所有 WHEN 分支都绑 null（如 SKIPPED 节点从未启动，
     * start_time 全为 null）时，驱动上报的参数类型不足以让 PG 推断 CASE 类型，
     * 仍会推断为 text 报 "column xxx is of type timestamp but expression is of type text"。
     * 因此对 timestamp/bigint 列的 CASE 整体再显式 :: 强转一次，全 null 时转换结果仍是 null。
     * <p>
     * 乐观锁：WHERE 按 (id, version) 成对匹配并 version+1，与 V3.2.2"用乐观锁防 sync
     * 并发覆盖"的意图一致。version 不匹配的行（已被 callback 等并发写入 bump 过）会被
     * 跳过不写，避免 sync 用过期的内存快照覆盖更新的状态；sync 每轮重新读库，下轮会
     * 基于最新 version 重试，语义上安全（宁可跳过一轮，不可覆盖新数据）。
     *
     * @return 实际更新的行数，可能小于 list.size()（存在版本冲突跳过时）
     */
    @Update("<script>" +
            "UPDATE node_execution SET " +
            "status = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.status,jdbcType=VARCHAR}</foreach> " +
            "END, " +
            "ds_task_instance_id = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.dsTaskInstanceId,jdbcType=BIGINT}</foreach> " +
            "END::bigint, " +
            "powerjob_instance_id = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.powerjobInstanceId,jdbcType=BIGINT}</foreach> " +
            "END::bigint, " +
            "start_time = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.startTime,jdbcType=TIMESTAMP}</foreach> " +
            "END::timestamp, " +
            "end_time = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.endTime,jdbcType=TIMESTAMP}</foreach> " +
            "END::timestamp, " +
            "duration_ms = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.durationMs,jdbcType=BIGINT}</foreach> " +
            "END::bigint, " +
            "error_message = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.errorMessage,jdbcType=VARCHAR}</foreach> " +
            "END, " +
            "output_info = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.outputInfo,jdbcType=VARCHAR}</foreach> " +
            "END, " +
            "sync_job_history_id = CASE id " +
            "<foreach collection='list' item='item'>WHEN #{item.id} THEN #{item.syncJobHistoryId,jdbcType=BIGINT}</foreach> " +
            "END::bigint, " +
            "version = version + 1 " +
            "WHERE (id, version) IN " +
            "<foreach collection='list' item='item' open='(' separator=',' close=')'>" +
            "(#{item.id}, #{item.version,jdbcType=INTEGER})" +
            "</foreach>" +
            "</script>")
    int updateBatch(@Param("list") List<NodeExecution> list);
}
