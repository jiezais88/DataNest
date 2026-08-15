package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.dto.DagExecutionStatsDTO;
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

    /** P3：按 PowerJob 工作流实例 ID 反查执行记录（cron 触发补齐用） */
    @Select("SELECT * FROM dag_execution WHERE powerjob_wf_instance_id = #{wfInstanceId} LIMIT 1")
    DagExecution selectByPowerjobWfInstanceId(@Param("wfInstanceId") Long wfInstanceId);

    @Select("SELECT * FROM dag_execution WHERE status = 'RUNNING'")
    List<DagExecution> selectRunning();

    /**
     * 队列等待池待调度实例（Sprint 11 F3）：按队列 + 优先级 DESC + 创建时间 ASC 排序（QU-6 高优先先执行）。
     * 仅返回该队列尚未被调度（powerjob_wf_instance_id IS NULL）的 WAITING，避免对账期间重复触发。
     */
    @Select("SELECT * FROM dag_execution WHERE status = 'WAITING' AND queue_name = #{queueName} "
            + "AND powerjob_wf_instance_id IS NULL "
            + "ORDER BY priority DESC, created_at ASC LIMIT #{limit}")
    List<DagExecution> selectWaitingToDispatch(@Param("queueName") String queueName, @Param("limit") int limit);

    /** 全部 WAITING 实例（对账兜底用，不区分是否有 wfInstanceId） */
    @Select("SELECT * FROM dag_execution WHERE status = 'WAITING' ORDER BY priority DESC, created_at ASC")
    List<DagExecution> selectAllWaiting();

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

    /**
     * Sprint 11 F3：指定 DAG 集合、自 since 起的执行次数（按 dag_id 分组聚合，供队列抽屉「7 天执行次数」列）。
     * 一次批量查询避免 N+1。
     */
    @Select("<script>"
            + "SELECT dag_id, COUNT(*) AS cnt FROM dag_execution"
            + " WHERE dag_id IN <foreach collection='dagIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND start_time &gt;= #{since}"
            + " GROUP BY dag_id"
            + "</script>")
    List<java.util.Map<String, Object>> countByDagIdsSince(@Param("dagIds") List<Long> dagIds,
                                                           @Param("since") LocalDateTime since);

    /**
     * 执行状态统计（列表页顶部统计卡，按时间范围聚合），避免前端拉全量列表计数。
     */
    @Select("<script>" +
            "SELECT " +
            "  COUNT(*) FILTER (WHERE status = 'RUNNING') AS running, " +
            "  COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success, " +
            "  COUNT(*) FILTER (WHERE status = 'FAILED') AS failed, " +
            "  COUNT(*) FILTER (WHERE status = 'TERMINATED') AS terminated " +
            "FROM dag_execution " +
            "<where>" +
            "  <if test='startTimeFrom != null'> AND start_time &gt;= #{startTimeFrom} </if>" +
            "  <if test='startTimeTo != null'> AND start_time &lt;= #{startTimeTo} </if>" +
            "</where>" +
            "</script>")
    DagExecutionStatsDTO selectStats(@Param("startTimeFrom") LocalDateTime startTimeFrom,
                                     @Param("startTimeTo") LocalDateTime startTimeTo);
}
