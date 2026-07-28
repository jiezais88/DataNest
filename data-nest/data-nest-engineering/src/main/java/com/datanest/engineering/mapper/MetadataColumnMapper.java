package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.MetadataColumn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MetadataColumnMapper extends BaseMapper<MetadataColumn> {

    @Delete("<script>DELETE FROM metadata_column WHERE table_id IN <foreach collection='tableIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteByTableIds(@Param("tableIds") List<Long> tableIds);
}
