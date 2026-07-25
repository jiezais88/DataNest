package com.datanest.engineering.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataTableMapper {

    @Select("SELECT id FROM metadata_table WHERE datasource_id = #{datasourceId}")
    List<Long> selectIdsByDatasourceId(@Param("datasourceId") Long datasourceId);

    @Delete("DELETE FROM metadata_table WHERE datasource_id = #{datasourceId}")
    int deleteByDatasourceId(@Param("datasourceId") Long datasourceId);
}
