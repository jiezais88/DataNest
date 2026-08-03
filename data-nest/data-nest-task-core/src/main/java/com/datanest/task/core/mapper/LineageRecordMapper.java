package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.dto.ColumnRef;
import com.datanest.task.core.dto.LineageColumnLinkDTO;
import com.datanest.task.core.dto.LineageTableEdge;
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
            "INSERT INTO lineage_record (source_table, target_table, source_column, target_column, dag_id, dag_name, " +
            "node_id, node_name, execution_id, lineage_type, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.sourceTable}, #{item.targetTable}, #{item.sourceColumn}, #{item.targetColumn}, " +
            "#{item.dagId}, #{item.dagName}, #{item.nodeId}, #{item.nodeName}, #{item.executionId}, #{item.lineageType}, #{item.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<LineageRecord> records);

    // ==================== 表级血缘图谱 ====================

    /**
     * 目标表是集合中任一表的边（上游边），用于 BFS 向上溯源。
     */
    @Select("<script>" +
            "SELECT DISTINCT source_table AS sourceTable, target_table AS targetTable, lineage_type AS lineageType " +
            "FROM lineage_record " +
            "WHERE source_table IS NOT NULL AND target_table IS NOT NULL " +
            "AND target_table IN " +
            "<foreach collection='tables' item='t' open='(' separator=',' close=')'>#{t}</foreach>" +
            "</script>")
    List<LineageTableEdge> selectUpstreamEdges(@Param("tables") List<String> tables);

    /**
     * 源表是集合中任一表的边（下游边），用于 BFS 向下溯源。
     */
    @Select("<script>" +
            "SELECT DISTINCT source_table AS sourceTable, target_table AS targetTable, lineage_type AS lineageType " +
            "FROM lineage_record " +
            "WHERE source_table IS NOT NULL AND target_table IS NOT NULL " +
            "AND source_table IN " +
            "<foreach collection='tables' item='t' open='(' separator=',' close=')'>#{t}</foreach>" +
            "</script>")
    List<LineageTableEdge> selectDownstreamEdges(@Param("tables") List<String> tables);

    // ==================== 字段级血缘图谱 ====================

    /**
     * 目标列属于 refs 中任一时，返回以这些列为下游的字段链路（向上追溯）。
     */
    @Select("<script>" +
            "SELECT DISTINCT source_table AS sourceTable, source_column AS sourceColumn, " +
            "target_table AS targetTable, target_column AS targetColumn, lineage_type AS lineageType, " +
            "dag_id AS dagId, dag_name AS dagName, node_id AS nodeId, node_name AS nodeName, " +
            "execution_id AS executionId, created_at AS createdAt " +
            "FROM lineage_record " +
            "WHERE source_column IS NOT NULL AND target_column IS NOT NULL " +
            "AND (" +
            "<foreach collection='refs' item='r' separator=' OR '>" +
            "(target_table = #{r.table} AND target_column = #{r.column})" +
            "</foreach>" +
            ") " +
            "</script>")
    List<LineageColumnLinkDTO> selectLinksByTargets(@Param("refs") List<ColumnRef> refs);

    /**
     * 源列属于 refs 中任一时，返回以这些列为上游的字段链路（向下追溯）。
     */
    @Select("<script>" +
            "SELECT DISTINCT source_table AS sourceTable, source_column AS sourceColumn, " +
            "target_table AS targetTable, target_column AS targetColumn, lineage_type AS lineageType, " +
            "dag_id AS dagId, dag_name AS dagName, node_id AS nodeId, node_name AS nodeName, " +
            "execution_id AS executionId, created_at AS createdAt " +
            "FROM lineage_record " +
            "WHERE source_column IS NOT NULL AND target_column IS NOT NULL " +
            "AND (" +
            "<foreach collection='refs' item='r' separator=' OR '>" +
            "(source_table = #{r.table} AND source_column = #{r.column})" +
            "</foreach>" +
            ") " +
            "</script>")
    List<LineageColumnLinkDTO> selectLinksBySources(@Param("refs") List<ColumnRef> refs);
}
