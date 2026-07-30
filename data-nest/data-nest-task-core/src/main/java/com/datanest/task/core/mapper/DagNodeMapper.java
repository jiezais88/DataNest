package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagNodeMapper extends BaseMapper<DagNode> {

    @Select("SELECT * FROM dag_node WHERE dag_id = #{dagId} ORDER BY id ASC")
    List<DagNode> selectByDagId(@Param("dagId") Long dagId);

    /**
     * ADR-S3-009：通过 dag_node.config LIKE 找引用了某 syncJobId 的 DAG 列表
     * 配合 idx_dag_node_config_pattern 索引高效查询
     */
    @Select("SELECT DISTINCT dag_id FROM dag_node WHERE config LIKE #{pattern}")
    List<Long> selectDagIdsReferencingSyncJob(@Param("pattern") String pattern);
}
