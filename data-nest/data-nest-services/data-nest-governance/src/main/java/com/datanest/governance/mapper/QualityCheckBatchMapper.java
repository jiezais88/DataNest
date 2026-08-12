package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.dto.QualityCheckStatsDTO;
import com.datanest.governance.entity.QualityCheckBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface QualityCheckBatchMapper extends BaseMapper<QualityCheckBatch> {

    /**
     * 批次状态统计（列表页顶部统计卡）：按时间范围条件聚合，避免前端拉全量列表计数。
     */
    @Select("<script>" +
            "SELECT " +
            "  COUNT(*) FILTER (WHERE status = 'RUNNING') AS running, " +
            "  COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success, " +
            "  COUNT(*) FILTER (WHERE status = 'PARTIAL_FAILED') AS partial, " +
            "  COUNT(*) FILTER (WHERE status = 'FAILED') AS failed " +
            "FROM quality_check_batch " +
            "<where>" +
            "  <if test='startTimeFrom != null'> AND started_at &gt;= #{startTimeFrom} </if>" +
            "  <if test='startTimeTo != null'> AND started_at &lt;= #{startTimeTo} </if>" +
            "</where>" +
            "</script>")
    QualityCheckStatsDTO selectStats(@Param("startTimeFrom") LocalDateTime startTimeFrom,
                                     @Param("startTimeTo") LocalDateTime startTimeTo);
}
