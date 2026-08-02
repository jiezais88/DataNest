package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.MetadataTable;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataTableMapper extends BaseMapper<MetadataTable> {

    @Select("SELECT DISTINCT database_name FROM metadata_table WHERE datasource_id = #{datasourceId} AND source_status = 'ONLINE' ORDER BY database_name")
    List<String> selectDatabasesByDatasourceId(@Param("datasourceId") Long datasourceId);

    @Select("SELECT DISTINCT schema_name FROM metadata_table WHERE datasource_id = #{datasourceId} AND database_name = #{databaseName} AND source_status = 'ONLINE' ORDER BY schema_name")
    List<String> selectSchemasByDatasourceIdAndDatabase(@Param("datasourceId") Long datasourceId,
                                                        @Param("databaseName") String databaseName);

    @Select("""
            SELECT t.id AS id,
                   t.datasource_id AS datasource_id,
                   t.database_name AS database_name,
                   t.schema_name AS schema_name,
                   t.table_name AS table_name,
                   t.table_comment AS table_comment,
                   t.manual_comment AS manual_comment,
                   t.source_status AS source_status,
                   t.source_type AS source_type,
                   t.last_collect_history_id AS last_collect_history_id,
                   t.created_by AS created_by,
                   t.updated_by AS updated_by,
                   t.created_at AS created_at,
                   t.updated_at AS updated_at,
                   h.ended_at AS last_collect_time,
                   h.task_name AS source_task_name,
                   ds.name AS datasource_name,
                   ds.type AS datasource_type,
                   (SELECT COUNT(*) FROM metadata_column c WHERE c.table_id = t.id AND c.source_status = 'ONLINE') AS column_count
            FROM metadata_table t
                     LEFT JOIN collect_history h ON h.id = t.last_collect_history_id
                     LEFT JOIN datasource_connection ds ON ds.id = t.datasource_id
            WHERE t.datasource_id = #{datasourceId}
              AND t.database_name = #{databaseName}
              AND COALESCE(t.schema_name, '') = COALESCE(#{schemaName}, '')
              AND t.source_status = 'ONLINE'
            ORDER BY t.table_name
            """)
    List<MetadataTable> selectTablesByDatasourceDatabaseSchema(@Param("datasourceId") Long datasourceId,
                                                               @Param("databaseName") String databaseName,
                                                               @Param("schemaName") String schemaName);

    @Select("""
            SELECT t.id AS id,
                   t.datasource_id AS datasource_id,
                   t.database_name AS database_name,
                   t.schema_name AS schema_name,
                   t.table_name AS table_name,
                   t.table_comment AS table_comment,
                   t.manual_comment AS manual_comment,
                   t.source_status AS source_status,
                   t.source_type AS source_type,
                   t.task_source_type AS task_source_type,
                   t.source_dag_id AS source_dag_id,
                   t.source_dag_name AS source_dag_name,
                   t.source_node_id AS source_node_id,
                   t.source_node_name AS source_node_name,
                   t.last_collect_history_id AS last_collect_history_id,
                   t.created_by AS created_by,
                   t.updated_by AS updated_by,
                   t.created_at AS created_at,
                   t.updated_at AS updated_at,
                   h.ended_at AS last_collect_time,
                   h.task_name AS source_task_name,
                   ds.name AS datasource_name,
                   ds.type AS datasource_type,
                   (SELECT COUNT(*) FROM metadata_column c WHERE c.table_id = t.id AND c.source_status = 'ONLINE') AS column_count
            FROM metadata_table t
                     LEFT JOIN collect_history h ON h.id = t.last_collect_history_id
                     LEFT JOIN datasource_connection ds ON ds.id = t.datasource_id
            WHERE t.id = #{tableId}
            """)
    MetadataTable selectTableDetailById(@Param("tableId") Long tableId);

    @Select("SELECT id FROM metadata_table WHERE datasource_id = #{datasourceId}")
    List<Long> selectIdsByDatasourceId(@Param("datasourceId") Long datasourceId);

    @Delete("DELETE FROM metadata_table WHERE datasource_id = #{datasourceId}")
    int deleteByDatasourceId(@Param("datasourceId") Long datasourceId);

    /**
     * 按数据库名、模式名、表名模糊搜索，返回匹配路径。
     * LIMIT 防止通配符/常见词关键词导致全表返回；调用方（governance MetadataService）另有空白/通配符关键词拦截与结果截断兜底。
     */
    @Select("""
            SELECT
                t.id AS id,
                t.datasource_id AS datasource_id,
                t.database_name AS database_name,
                t.schema_name AS schema_name,
                t.table_name AS table_name,
                t.source_type AS source_type,
                ds.name AS datasource_name,
                ds.type AS datasource_type,
                (SELECT COUNT(*) FROM metadata_column c WHERE c.table_id = t.id AND c.source_status = 'ONLINE') AS column_count
            FROM metadata_table t
                     LEFT JOIN datasource_connection ds ON ds.id = t.datasource_id
            WHERE t.source_status = 'ONLINE'
              AND (
                t.database_name LIKE CONCAT('%', #{keyword}, '%')
                OR t.schema_name LIKE CONCAT('%', #{keyword}, '%')
                OR t.table_name LIKE CONCAT('%', #{keyword}, '%')
              )
            ORDER BY t.datasource_id, t.database_name, COALESCE(t.schema_name, ''), t.table_name
            LIMIT 100
            """)
    List<MetadataTable> searchTablesByKeyword(@Param("keyword") String keyword);
}
