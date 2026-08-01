package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagAlertConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DagAlertConfigMapper extends BaseMapper<DagAlertConfig> {

    @Select("SELECT * FROM dag_alert_config WHERE dag_id IS NULL ORDER BY id ASC LIMIT 1")
    DagAlertConfig selectGlobal();

    @Select("SELECT * FROM dag_alert_config WHERE dag_id = #{dagId} ORDER BY id ASC LIMIT 1")
    DagAlertConfig selectByDagId(@Param("dagId") Long dagId);
}
