package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagNode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagNodeMapper extends BaseMapper<DagNode> {

    @Select("SELECT * FROM dag_node WHERE dag_id = #{dagId} ORDER BY id ASC")
    List<DagNode> selectByDagId(@Param("dagId") Long dagId);

    /**
     * Sprint 3 性能优化：真正的批量插入
     */
    @Insert("<script>" +
            "INSERT INTO dag_node (id, dag_id, node_id, node_name, node_type, position_x, position_y, config, ds_task_code, created_by, updated_by, created_at, updated_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.dagId}, #{item.nodeId}, #{item.nodeName}, #{item.nodeType}, #{item.positionX}, #{item.positionY}, #{item.config}, #{item.dsTaskCode}, #{item.createdBy}, #{item.updatedBy}, #{item.createdAt}, #{item.updatedAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<DagNode> list);

    /**
     * ADR-S3-009：通过 dag_node.config 正则找引用了某 syncJobId 的 DAG 列表。
     * 兼容前端/后端不同 JSON 序列化格式（带不带空格、数值或字符串）。
     * PostgreSQL 专用：~ 运算符支持正则匹配。
     */
    @Select("SELECT DISTINCT dag_id FROM dag_node WHERE config ~ #{pattern}")
    List<Long> selectDagIdsReferencingSyncJob(@Param("pattern") String pattern);
}
