package com.datanest.dataservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.dataservice.dto.CallStatAgg;
import com.datanest.dataservice.dto.OverviewAgg;
import com.datanest.dataservice.dto.RefCount;
import com.datanest.dataservice.dto.StatusAgg;
import com.datanest.dataservice.dto.StatusBreakdownDTO;
import com.datanest.dataservice.dto.TrendAgg;
import com.datanest.dataservice.entity.ApiCallLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API 调用统计明细 Mapper（F3 异步写入；F2 用于近 7 天调用聚合，识别僵尸 Key / API 热度）。
 */
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {

    /** 批量统计每个 Key 自 since 以来的调用数（Key 列表「近 7 天调用」列，0 = 僵尸 Key） */
    @Select("<script>SELECT key_id AS refId, COUNT(*) AS cnt FROM api_call_log WHERE created_at &gt;= #{since} AND key_id IN "
            + "<foreach collection='keyIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY key_id</script>")
    List<RefCount> countCallsByKeyIdsSince(@Param("keyIds") List<Long> keyIds, @Param("since") LocalDateTime since);

    /** 批量统计每个 API 自 since 以来的调用数（API 列表/详情「近 7 天调用」） */
    @Select("<script>SELECT api_id AS refId, COUNT(*) AS cnt FROM api_call_log WHERE created_at &gt;= #{since} AND api_id IN "
            + "<foreach collection='apiIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY api_id</script>")
    List<RefCount> countCallsByApiIdsSince(@Param("apiIds") List<Long> apiIds, @Param("since") LocalDateTime since);

    // ===== F3 调用统计聚合（api_call_log 位于 PG，percentile_cont/FILTER 为 PG 语法） =====

    /** 全局概览：总调用量 + 成功数 + 限流命中 + P95 耗时（since 以来） */
    @Select("SELECT COUNT(*) AS totalCalls, "
            + "COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 300) AS successCalls, "
            + "COUNT(*) FILTER (WHERE status_code = 429) AS rateLimited, "
            + "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE duration_ms IS NOT NULL), 0) AS p95 "
            + "FROM api_call_log WHERE created_at >= #{since}")
    OverviewAgg overviewSince(@Param("since") LocalDateTime since);

    /** 单 API 概览：总调用量 + 成功数 + 限流命中 + P95 耗时 */
    @Select("SELECT COUNT(*) AS totalCalls, "
            + "COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 300) AS successCalls, "
            + "COUNT(*) FILTER (WHERE status_code = 429) AS rateLimited, "
            + "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE duration_ms IS NOT NULL), 0) AS p95 "
            + "FROM api_call_log WHERE created_at >= #{since} AND api_id = #{apiId}")
    OverviewAgg overviewByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since);

    /** 单 API 平均耗时 */
    @Select("SELECT COALESCE(AVG(duration_ms), 0) FROM api_call_log WHERE created_at >= #{since} AND api_id = #{apiId} AND duration_ms IS NOT NULL")
    Double avgDurationByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since);

    /** 单 API 自 since（今日 0 点）以来的调用数 */
    @Select("SELECT COUNT(*) FROM api_call_log WHERE created_at >= #{since} AND api_id = #{apiId}")
    Long countCallsByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since);

    /** 按 api_id 分组统计（健康分布：总调用/失败/限流/P95） */
    @Select("SELECT api_id AS refId, COUNT(*) AS totalCalls, "
            + "COUNT(*) FILTER (WHERE status_code >= 400) AS failedCalls, "
            + "COUNT(*) FILTER (WHERE status_code = 429) AS rateLimited, "
            + "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE duration_ms IS NOT NULL), 0) AS p95 "
            + "FROM api_call_log WHERE created_at >= #{since} AND api_id IS NOT NULL GROUP BY api_id")
    List<CallStatAgg> statByApiSince(@Param("since") LocalDateTime since);

    /** Top API 调用排行 */
    @Select("SELECT api_id AS refId, COUNT(*) AS cnt FROM api_call_log "
            + "WHERE created_at >= #{since} AND api_id IS NOT NULL GROUP BY api_id ORDER BY COUNT(*) DESC LIMIT #{limit}")
    List<RefCount> topApisSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** Top Key 调用排行 */
    @Select("SELECT key_id AS refId, COUNT(*) AS cnt FROM api_call_log "
            + "WHERE created_at >= #{since} AND key_id IS NOT NULL GROUP BY key_id ORDER BY COUNT(*) DESC LIMIT #{limit}")
    List<RefCount> topKeysSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 错误码分布（4xx/5xx TopN） */
    @Select("SELECT status_code AS statusCode, COUNT(*) AS cnt FROM api_call_log "
            + "WHERE created_at >= #{since} AND status_code >= 400 GROUP BY status_code ORDER BY COUNT(*) DESC LIMIT #{limit}")
    List<StatusAgg> errorCodesSince(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 429 限流命中最多的 API（错误码分布提示「命中集中在 X」，0 或 1 条） */
    @Select("SELECT api_id AS refId, COUNT(*) AS cnt FROM api_call_log "
            + "WHERE created_at >= #{since} AND status_code = 429 AND api_id IS NOT NULL "
            + "GROUP BY api_id ORDER BY COUNT(*) DESC LIMIT 1")
    RefCount top429ApiSince(@Param("since") LocalDateTime since);

    /** 全局调用量趋势（unit=hour/day），total 调用量 + failed 失败数 */
    @Select("SELECT date_trunc(#{unit}, created_at) AS bucket, COUNT(*) AS total, "
            + "COUNT(*) FILTER (WHERE status_code >= 400) AS failed "
            + "FROM api_call_log WHERE created_at >= #{since} GROUP BY bucket ORDER BY bucket")
    List<TrendAgg> trendSince(@Param("since") LocalDateTime since, @Param("unit") String unit);

    /** 限流命中趋势（429 按时间桶），total 存限流数 */
    @Select("SELECT date_trunc(#{unit}, created_at) AS bucket, COUNT(*) AS total "
            + "FROM api_call_log WHERE created_at >= #{since} AND status_code = 429 GROUP BY bucket ORDER BY bucket")
    List<TrendAgg> rateLimitTrendSince(@Param("since") LocalDateTime since, @Param("unit") String unit);

    /** 单 API 调用量趋势（unit=hour/day） */
    @Select("SELECT date_trunc(#{unit}, created_at) AS bucket, COUNT(*) AS total, "
            + "COUNT(*) FILTER (WHERE status_code >= 400) AS failed "
            + "FROM api_call_log WHERE created_at >= #{since} AND api_id = #{apiId} GROUP BY bucket ORDER BY bucket")
    List<TrendAgg> trendByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since,
                                   @Param("unit") String unit);

    /** 单 API 今日小时调用分布（按小时分桶） */
    @Select("SELECT date_trunc('hour', created_at) AS bucket, COUNT(*) AS total "
            + "FROM api_call_log WHERE created_at >= #{since} AND api_id = #{apiId} GROUP BY bucket ORDER BY bucket")
    List<TrendAgg> hourlyByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since);

    /** 单 API 调用方 Key 排行（TopN） */
    @Select("SELECT key_id AS refId, COUNT(*) AS cnt FROM api_call_log "
            + "WHERE created_at >= #{since} AND api_id = #{apiId} AND key_id IS NOT NULL "
            + "GROUP BY key_id ORDER BY COUNT(*) DESC LIMIT #{limit}")
    List<RefCount> topKeysByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since,
                                     @Param("limit") int limit);

    /** 单 API 状态码三档汇总（2xx 成功 / 4xx 客户端 / 5xx 服务端） */
    @Select("SELECT COUNT(*) FILTER (WHERE status_code >= 200 AND status_code < 300) AS success, "
            + "COUNT(*) FILTER (WHERE status_code >= 400 AND status_code < 500) AS clientError, "
            + "COUNT(*) FILTER (WHERE status_code >= 500) AS serverError "
            + "FROM api_call_log WHERE created_at >= #{since} AND api_id = #{apiId}")
    StatusBreakdownDTO statusBreakdownByApiSince(@Param("apiId") Long apiId, @Param("since") LocalDateTime since);

    /** 统计 since 以来的全部调用数（API 管理列表页「近 7 天总调用」统计卡） */
    @Select("<script>SELECT COUNT(*) FROM api_call_log WHERE created_at &gt;= #{since}</script>")
    Long countCallsSince(@Param("since") LocalDateTime since);
}
