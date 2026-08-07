package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.DagEdge;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagEdgeMapper extends BaseMapper<DagEdge> {

    @Select("SELECT * FROM dag_edge WHERE dag_id = #{dagId} ORDER BY id ASC")
    List<DagEdge> selectByDagId(@Param("dagId") Long dagId);

    /**
     * Sprint 3 性能优化：真正的批量插入
     */
    @Insert("<script>" +
            "INSERT INTO dag_edge (id, dag_id, edge_id, source_node_id, target_node_id, created_by, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.dagId}, #{item.edgeId}, #{item.sourceNodeId}, #{item.targetNodeId}, #{item.createdBy}, #{item.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<DagEdge> list);
}
