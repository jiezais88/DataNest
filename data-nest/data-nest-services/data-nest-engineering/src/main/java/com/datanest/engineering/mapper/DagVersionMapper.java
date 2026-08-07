package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.DagVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DagVersionMapper extends BaseMapper<DagVersion> {

    @Select("SELECT * FROM dag_version WHERE dag_id = #{dagId} ORDER BY version_no DESC")
    List<DagVersion> selectByDagId(@Param("dagId") Long dagId);

    @Select("SELECT * FROM dag_version WHERE dag_id = #{dagId} AND version_no = #{versionNo} LIMIT 1")
    DagVersion selectByDagIdAndVersionNo(@Param("dagId") Long dagId, @Param("versionNo") Integer versionNo);

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM dag_version WHERE dag_id = #{dagId}")
    Integer selectMaxVersionNo(@Param("dagId") Long dagId);
}
