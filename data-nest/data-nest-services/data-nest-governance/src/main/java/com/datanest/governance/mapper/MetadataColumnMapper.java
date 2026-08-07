package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.MetadataColumn;
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

    /**
     * Sprint 7 F1：按字段名/字段注释模糊搜索，返回命中的 table_id 集合（资产搜索「字段」维度）。
     * LIMIT 防止常见词关键词导致全表返回。
     */
    @Select("""
            SELECT DISTINCT table_id FROM metadata_column
            WHERE source_status = 'ONLINE'
              AND (
                column_name LIKE CONCAT('%', #{keyword}, '%')
                OR column_comment LIKE CONCAT('%', #{keyword}, '%')
                OR manual_comment LIKE CONCAT('%', #{keyword}, '%')
              )
            LIMIT 500
            """)
    List<Long> selectTableIdsByColumnKeyword(@Param("keyword") String keyword);
}
