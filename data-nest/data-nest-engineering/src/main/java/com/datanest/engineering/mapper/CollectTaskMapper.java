package com.datanest.engineering.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.engineering.entity.CollectTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CollectTaskMapper extends BaseMapper<CollectTask> {

    @Select("SELECT * FROM collect_task WHERE datasource_id = #{datasourceId} AND status != 'DELETED'")
    List<CollectTask> selectActiveByDatasourceId(@Param("datasourceId") Long datasourceId);
}
