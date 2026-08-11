package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.QualityScore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 表级质量评分 Mapper（Sprint 6 NG8）。
 * <p>
 * 批量按表名 IN 查询由调用方用 {@code QueryWrapper.in("table_name", ...)} 实现，
 * 血缘图谱回填时一次查询，避免 N+1。
 */
@Mapper
public interface QualityScoreMapper extends BaseMapper<QualityScore> {

    /**
     * Sprint 8 F3：数据源质量对比——按数据源分组平均评分（当前最新评分 ⋈ ONLINE 表）。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写。
     */
    @Select("""
            <script>
            SELECT t.datasource_id AS datasource_id,
                   AVG(s.score) AS avg_score,
                   COUNT(*) AS table_count
            FROM quality_score s
            JOIN metadata_table t ON t.id = s.table_id
            WHERE t.source_status = 'ONLINE'
            <if test="tableIds != null">
              AND t.id IN
              <foreach collection="tableIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            </if>
            GROUP BY t.datasource_id
            ORDER BY avg_score DESC
            </script>
            """)
    List<Map<String, Object>> selectDatasourceScoreComparison(@Param("tableIds") List<Long> tableIds);
}
