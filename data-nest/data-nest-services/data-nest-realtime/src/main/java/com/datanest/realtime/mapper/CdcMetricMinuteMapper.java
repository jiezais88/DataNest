package com.datanest.realtime.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.realtime.entity.CdcMetricMinute;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CDC 管道分钟级指标历史 Mapper（Sprint 9 F1）。
 * <p>
 * 分钟快照 upsert（ON CONFLICT）与 30 天清理；趋势查询按 range 分桶聚合（1h/6h 原始分钟点、
 * 24h 按 5 分钟桶、7d 按小时桶）。桶表达式由 service 层白名单生成（固定字符串拼接，无注入面）。
 */
@Mapper
public interface CdcMetricMinuteMapper extends BaseMapper<CdcMetricMinute> {

    /**
     * 幂等 upsert 当前整分钟快照（pipeline_id + minute_at 冲突时覆盖各指标列，created_at 保留首次）。
     */
    @Insert("INSERT INTO cdc_metric_minute (id, pipeline_id, minute_at, lag_avg_seconds, lag_max_seconds, " +
            "records_per_second_avg, num_restarts, total_changes, created_at) " +
            "VALUES (#{id}, #{pipelineId}, #{minuteAt}, #{lagAvgSeconds}, #{lagMaxSeconds}, " +
            "#{recordsPerSecondAvg}, #{numRestarts}, #{totalChanges}, NOW()) " +
            "ON CONFLICT (pipeline_id, minute_at) DO UPDATE SET " +
            "lag_avg_seconds = EXCLUDED.lag_avg_seconds, " +
            "lag_max_seconds = EXCLUDED.lag_max_seconds, " +
            "records_per_second_avg = EXCLUDED.records_per_second_avg, " +
            "num_restarts = EXCLUDED.num_restarts, " +
            "total_changes = EXCLUDED.total_changes")
    void upsertMinute(@Param("id") Long id, @Param("pipelineId") Long pipelineId,
                      @Param("minuteAt") LocalDateTime minuteAt,
                      @Param("lagAvgSeconds") Integer lagAvgSeconds, @Param("lagMaxSeconds") Integer lagMaxSeconds,
                      @Param("recordsPerSecondAvg") Double recordsPerSecondAvg,
                      @Param("numRestarts") Integer numRestarts, @Param("totalChanges") Long totalChanges);

    /** 清理早于保留边界的分钟快照，返回删除行数 */
    @Delete("DELETE FROM cdc_metric_minute WHERE minute_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);

    /**
     * 趋势查询：按桶表达式聚合（bucketSql 由 service 白名单生成）。
     *
     * @param bucketSql date_trunc/算术桶表达式，如 date_trunc('hour', minute_at)
     * @return [{minute_at, lag_avg_seconds, lag_max_seconds, records_per_second_avg}]
     */
    @Select("SELECT ${bucketSql} AS minute_at, " +
            "AVG(lag_avg_seconds) AS lag_avg_seconds, " +
            "MAX(lag_max_seconds) AS lag_max_seconds, " +
            "AVG(records_per_second_avg) AS records_per_second_avg " +
            "FROM cdc_metric_minute " +
            "WHERE pipeline_id = #{pipelineId} AND minute_at >= #{from} AND minute_at < #{to} " +
            "GROUP BY ${bucketSql} ORDER BY minute_at")
    List<Map<String, Object>> selectTrend(@Param("pipelineId") Long pipelineId,
                                          @Param("bucketSql") String bucketSql,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
