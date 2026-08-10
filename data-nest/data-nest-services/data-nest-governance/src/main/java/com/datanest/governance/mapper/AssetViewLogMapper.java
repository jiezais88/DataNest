package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.AssetViewLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface AssetViewLogMapper extends BaseMapper<AssetViewLog> {

    /** 热度埋点：按 (table_id, 当天) upsert 累加 view_count（Sprint 8 DC-09，DC-05 决策不引入 Redis）。 */
    @Insert("""
            INSERT INTO asset_view_log (id, table_id, view_date, view_count, updated_at)
            VALUES (#{id}, #{tableId}, #{viewDate}, 1, CURRENT_TIMESTAMP)
            ON CONFLICT (table_id, view_date)
            DO UPDATE SET view_count = asset_view_log.view_count + 1, updated_at = CURRENT_TIMESTAMP
            """)
    int upsertIncrement(@Param("id") Long id, @Param("tableId") Long tableId, @Param("viewDate") LocalDate viewDate);

    /**
     * 按表 ID 集合聚合最近 N 天访问数（browse sort=hot / 详情页热度值回填用，避免 N+1）。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写。
     */
    @Select("""
            <script>
            SELECT table_id AS table_id, SUM(view_count) AS view_count
            FROM asset_view_log
            WHERE view_date >= #{sinceDate}
              AND table_id IN
            <foreach collection="tableIds" item="tid" open="(" separator="," close=")">#{tid}</foreach>
            GROUP BY table_id
            </script>
            """)
    List<Map<String, Object>> sumViewCountByTableIds(@Param("tableIds") List<Long> tableIds,
                                                     @Param("sinceDate") LocalDate sinceDate);

    /**
     * 热门数据表 Top N：最近 N 天热度降序，仅 ONLINE 表。
     * 注意别名用下划线小写：PostgreSQL 会把未加引号的驼峰别名折叠成小写。
     */
    @Select("""
            SELECT v.table_id AS table_id, v.view_count AS view_count
            FROM (SELECT table_id, SUM(view_count) AS view_count
                  FROM asset_view_log
                  WHERE view_date >= #{sinceDate}
                  GROUP BY table_id) v
                     JOIN metadata_table t ON t.id = v.table_id AND t.source_status = 'ONLINE'
            ORDER BY v.view_count DESC, v.table_id
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectHotTables(@Param("sinceDate") LocalDate sinceDate,
                                              @Param("limit") int limit);
}
