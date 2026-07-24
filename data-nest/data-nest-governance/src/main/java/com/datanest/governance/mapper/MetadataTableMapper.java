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

    @Select("SELECT * FROM metadata_table WHERE datasource_id = #{datasourceId} AND database_name = #{databaseName} AND COALESCE(schema_name, '') = COALESCE(#{schemaName}, '') ORDER BY table_name")
    List<MetadataTable> selectTablesByDatasourceDatabaseSchema(@Param("datasourceId") Long datasourceId,
                                                               @Param("databaseName") String databaseName,
                                                               @Param("schemaName") String schemaName);
}
