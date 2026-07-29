package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.MetadataColumn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataColumnMapper extends BaseMapper<MetadataColumn> {

    @Select("SELECT * FROM metadata_column WHERE table_id = #{tableId} AND source_status = 'ONLINE' ORDER BY ordinal_position, column_name")
    List<MetadataColumn> selectByTableId(@Param("tableId") Long tableId);
}
