package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.DagExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DagExecutionMapper extends BaseMapper<DagExecution> {

    @Select("SELECT * FROM dag_execution WHERE dag_id = #{dagId} ORDER BY start_time DESC")
    List<DagExecution> selectByDagId(@Param("dagId") Long dagId);

    @Select("SELECT * FROM dag_execution WHERE ds_process_instance_id = #{dsProcessInstanceId} LIMIT 1")
    DagExecution selectByDsProcessInstanceId(@Param("dsProcessInstanceId") Long dsProcessInstanceId);

    /** P3：按 PowerJob 工作流实例 ID 反查执行记录（cron 触发补齐用） */
    @Select("SELECT * FROM dag_execution WHERE powerjob_wf_instance_id = #{wfInstanceId} LIMIT 1")
    DagExecution selectByPowerjobWfInstanceId(@Param("wfInstanceId") Long wfInstanceId);

    @Select("SELECT * FROM dag_execution WHERE status = 'RUNNING'")
    List<DagExecution> selectRunning();

    /**
     * 每个 DAG 只取最新一条执行记录（PostgreSQL DISTINCT ON）。
     * 供 DAG 列表页展示 latestExecution 用，避免把项目下全部执行历史载入内存。
     * 排序与原 Java 侧逻辑一致：start_time 为空视为最旧（NULLS LAST），id DESC 兜底。
     */
    @Select("<script>"
            + "SELECT DISTINCT ON (dag_id) * FROM dag_execution"
            + " WHERE dag_id IN <foreach collection='dagIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY dag_id, start_time DESC NULLS LAST, id DESC"
            + "</script>")
    List<DagExecution> selectLatestByDagIds(@Param("dagIds") List<Long> dagIds);

    /**
     * 查询指定时间之前、指定状态（终态）的执行历史
     */
    @Select("SELECT * FROM dag_execution WHERE status IN ('SUCCESS', 'FAILED', 'TERMINATED') AND start_time < #{beforeTime} ORDER BY id LIMIT #{limit}")
    List<DagExecution> selectTerminalsBefore(@Param("beforeTime") LocalDateTime beforeTime, @Param("limit") int limit);
}
