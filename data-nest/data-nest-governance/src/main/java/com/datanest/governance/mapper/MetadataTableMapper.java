package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.MetadataTable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataTableMapper extends BaseMapper<MetadataTable> {

    @Select("SELECT DISTINCT database_name FROM metadata_table WHERE datasource_id = #{datasourceId} ORDER BY database_name")
    List<String> selectDatabasesByDatasourceId(@Param("datasourceId") Long datasourceId);

    @Select("SELECT DISTINCT schema_name FROM metadata_table WHERE datasource_id = #{datasourceId} AND database_name = #{databaseName} ORDER BY schema_name")
    List<String> selectSchemasByDatasourceIdAndDatabase(@Param("datasourceId") Long datasourceId,
                                                        @Param("databaseName") String databaseName);

    @Select("""
            SELECT t.*,
                   t.column_count AS column_count,
                   h.ended_at AS last_collect_time,
                   h.task_name AS source_task_name,
                   ds.name AS datasource_name,
                   ds.type AS datasource_type
            FROM metadata_table t
                     LEFT JOIN collect_history h ON h.id = t.last_collect_history_id
                     LEFT JOIN datasource_connection ds ON ds.id = t.datasource_id
            WHERE t.datasource_id = #{datasourceId}
              AND t.database_name = #{databaseName}
              AND COALESCE(t.schema_name, '') = COALESCE(#{schemaName}, '')
            ORDER BY t.table_name
            """)
    List<MetadataTable> selectTablesByDatasourceDatabaseSchema(@Param("datasourceId") Long datasourceId,
                                                               @Param("databaseName") String databaseName,
                                                               @Param("schemaName") String schemaName);

    @Select("""
            SELECT t.*,
                   t.column_count AS column_count,
                   h.ended_at AS last_collect_time,
                   h.task_name AS source_task_name,
                   ds.name AS datasource_name,
                   ds.type AS datasource_type
            FROM metadata_table t
                     LEFT JOIN collect_history h ON h.id = t.last_collect_history_id
                     LEFT JOIN datasource_connection ds ON ds.id = t.datasource_id
            WHERE t.id = #{tableId}
            """)
    MetadataTable selectTableDetailById(@Param("tableId") Long tableId);
}
