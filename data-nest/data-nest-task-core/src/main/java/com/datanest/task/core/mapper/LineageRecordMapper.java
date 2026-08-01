package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.LineageRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LineageRecordMapper extends BaseMapper<LineageRecord> {

    @Select("SELECT * FROM lineage_record WHERE target_table = #{targetTable} ORDER BY created_at DESC")
    List<LineageRecord> selectByTargetTable(@Param("targetTable") String targetTable);

    @Select("SELECT * FROM lineage_record WHERE dag_id = #{dagId} ORDER BY created_at DESC")
    List<LineageRecord> selectByDagId(@Param("dagId") Long dagId);

    @Insert("<script>" +
            "INSERT INTO lineage_record (source_table, target_table, dag_id, dag_name, node_id, node_name, execution_id, lineage_type, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.sourceTable}, #{item.targetTable}, #{item.dagId}, #{item.dagName}, #{item.nodeId}, #{item.nodeName}, #{item.executionId}, #{item.lineageType}, #{item.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<LineageRecord> records);
}
