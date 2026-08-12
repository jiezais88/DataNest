package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.dto.SyncJobHistoryStatsDTO;
import com.datanest.engineering.entity.SyncJobHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

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
}
