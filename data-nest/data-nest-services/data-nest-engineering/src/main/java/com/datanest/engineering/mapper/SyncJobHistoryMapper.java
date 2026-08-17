package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.dto.SyncJobHistoryStatsDTO;
import com.datanest.engineering.entity.SyncJobHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SyncJobHistoryMapper extends BaseMapper<SyncJobHistory> {

    /**
     * 执行状态统计（列表页顶部统计卡，按时间范围聚合），避免前端拉全量列表计数。
     */
    @Select("<script>" +
            "SELECT " +
            "  COUNT(*) FILTER (WHERE status = 'RUNNING') AS running, " +
            "  COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success, " +
            "  COUNT(*) FILTER (WHERE status = 'FAILED') AS failed, " +
            "  COUNT(*) FILTER (WHERE status = 'TERMINATED') AS terminated " +
            "FROM sync_job_history " +
            "<where>" +
            "  <if test='startTimeFrom != null'> AND start_time &gt;= #{startTimeFrom} </if>" +
            "  <if test='startTimeTo != null'> AND start_time &lt;= #{startTimeTo} </if>" +
            "</where>" +
            "</script>")
    SyncJobHistoryStatsDTO selectStats(@Param("startTimeFrom") LocalDateTime startTimeFrom,
                                       @Param("startTimeTo") LocalDateTime startTimeTo);

    /**
     * Sprint 11 F5 首页：按天聚合执行量（近 N 日），供 7 日趋势图 + KPI sparkline。
     */
    @Select("SELECT TO_CHAR(start_time, 'MM-dd') AS day, " +
            "  COUNT(*) AS total, " +
            "  COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success, " +
            "  COUNT(*) FILTER (WHERE status = 'FAILED') AS failed " +
            "FROM sync_job_history " +
            "WHERE start_time >= #{since} " +
            "GROUP BY TO_CHAR(start_time, 'MM-dd') " +
            "ORDER BY day")
    List<DagExecutionMapper.DagDailyStat> selectDailyStats(@Param("since") LocalDateTime since);

    /**
     * Sprint 11 F5：近 N 天内各同步任务最近一次 SUCCESS 时间（失败恢复判定：SUCCESS 必须晚于 FAILED 才算恢复）。
     */
    @Select("<script>"
            + "SELECT sync_job_id, MAX(start_time) AS last_success FROM sync_job_history"
            + " WHERE sync_job_id IN <foreach collection='jobIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND status = 'SUCCESS' AND start_time &gt;= #{since}"
            + " GROUP BY sync_job_id"
            + "</script>")
    List<java.util.Map<String, Object>> lastSuccessTimeByJobIdsSince(@Param("jobIds") List<Long> jobIds,
                                                                     @Param("since") LocalDateTime since);
}
