package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.QualityCheckDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface QualityCheckDetailMapper extends BaseMapper<QualityCheckDetail> {

    /**
     * Sprint 8 F3：质量报告 KPI 聚合（一条 SQL 出明细数/批次数/通过数/有效数/待处理问题数）。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写，导致 map key 取不到值。
     */
    @Select("""
            <script>
            SELECT COUNT(*) AS detail_count,
                   COUNT(DISTINCT batch_id) AS batch_count,
                   SUM(CASE WHEN result_level = 'PASS' THEN 1 ELSE 0 END) AS pass_count,
                   SUM(CASE WHEN result_level != 'UNAVAILABLE' THEN 1 ELSE 0 END) AS valid_count,
                   SUM(CASE WHEN result_level = 'SEVERE' THEN 1 ELSE 0 END) AS severe_count,
                   SUM(CASE WHEN result_level = 'WARNING' THEN 1 ELSE 0 END) AS warning_count
            FROM quality_check_detail
            WHERE created_at &gt;= #{start} AND created_at &lt;= #{end}
            <if test="tableIds != null">
              AND table_id IN
              <foreach collection="tableIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            </if>
            <if test="jobId != null">
              AND batch_id IN (SELECT id FROM quality_check_batch WHERE job_id = #{jobId})
            </if>
            </script>
            """)
    Map<String, Object> selectReportSummary(@Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end,
                                            @Param("tableIds") List<Long> tableIds,
                                            @Param("jobId") Long jobId);

    /**
     * Sprint 8 F3：四档分布趋势（按天聚合 PASS/WARNING/SEVERE/UNAVAILABLE 数量）。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写。
     */
    @Select("""
            <script>
            SELECT TO_CHAR(created_at, 'YYYY-MM-DD') AS day,
                   SUM(CASE WHEN result_level = 'PASS' THEN 1 ELSE 0 END) AS pass_count,
                   SUM(CASE WHEN result_level = 'WARNING' THEN 1 ELSE 0 END) AS warning_count,
                   SUM(CASE WHEN result_level = 'SEVERE' THEN 1 ELSE 0 END) AS severe_count,
                   SUM(CASE WHEN result_level = 'UNAVAILABLE' THEN 1 ELSE 0 END) AS unavailable_count
            FROM quality_check_detail
            WHERE created_at &gt;= #{start} AND created_at &lt;= #{end}
            <if test="tableIds != null">
              AND table_id IN
              <foreach collection="tableIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            </if>
            <if test="jobId != null">
              AND batch_id IN (SELECT id FROM quality_check_batch WHERE job_id = #{jobId})
            </if>
            GROUP BY day
            ORDER BY day
            </script>
            """)
    List<Map<String, Object>> selectDailyLevelTrend(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end,
                                                    @Param("tableIds") List<Long> tableIds,
                                                    @Param("jobId") Long jobId);
}
