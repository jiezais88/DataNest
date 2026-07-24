package com.datanest.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.governance.entity.CollectTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CollectTaskMapper extends BaseMapper<CollectTask> {

    @Select("SELECT id, name, datasource_id FROM collect_task WHERE datasource_id = #{datasourceId}")
    List<CollectTask> listByDatasourceId(@Param("datasourceId") Long datasourceId);
}
