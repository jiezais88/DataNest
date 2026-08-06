package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.SyncJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SyncJobMapper extends BaseMapper<SyncJob> {

    @Select("SELECT * FROM sync_job WHERE source_datasource_id = #{datasourceId}")
    List<SyncJob> selectBySourceDatasourceId(@Param("datasourceId") Long datasourceId);
}
