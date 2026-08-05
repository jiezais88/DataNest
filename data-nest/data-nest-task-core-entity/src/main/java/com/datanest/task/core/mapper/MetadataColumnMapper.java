package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.MetadataColumn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataColumnMapper extends BaseMapper<MetadataColumn> {

    @Select("SELECT * FROM metadata_column WHERE table_id = #{tableId} AND source_status = 'ONLINE' ORDER BY ordinal_position, column_name")
    List<MetadataColumn> selectByTableId(@Param("tableId") Long tableId);

    @Delete("<script>DELETE FROM metadata_column WHERE table_id IN <foreach item='id' index='index' collection='tableIds' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteByTableIds(@Param("tableIds") List<Long> tableIds);
}
