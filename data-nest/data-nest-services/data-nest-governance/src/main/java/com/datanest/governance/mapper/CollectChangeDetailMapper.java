package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.CollectChangeDetail;
import com.datanest.governance.entity.MetadataTable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CollectChangeDetailMapper extends BaseMapper<CollectChangeDetail> {

    /**
     * Sprint 8 F1：批量取每张表最近一次采集变更（我的关注变更动态，替代逐表 limit 1 的 N+1）。
     * DISTINCT ON 按 (database_name, COALESCE(schema_name,''), table_name) 三元组分组取 id 最大一条；
     * schema 参数侧同样 COALESCE（jdbcType=VARCHAR 兼容 null），与三元组匹配语义一致。
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (database_name, COALESCE(schema_name, ''), table_name)
                id, history_id, change_type, database_name, schema_name, table_name, column_name, old_value, new_value, created_at
            FROM collect_change_detail
            WHERE
            <foreach collection="tables" item="t" open="(" separator=" OR " close=")">
                (database_name = #{t.databaseName}
                 AND COALESCE(schema_name, '') = COALESCE(#{t.schemaName,jdbcType=VARCHAR}, '')
                 AND table_name = #{t.tableName})
            </foreach>
            ORDER BY database_name, COALESCE(schema_name, ''), table_name, id DESC
            </script>
            """)
    List<CollectChangeDetail> selectLatestByTableTriples(@Param("tables") List<MetadataTable> tables);
}
