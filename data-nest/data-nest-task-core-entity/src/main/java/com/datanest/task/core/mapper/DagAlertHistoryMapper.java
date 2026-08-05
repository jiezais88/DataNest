package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.task.core.entity.DagAlertHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DagAlertHistoryMapper extends BaseMapper<DagAlertHistory> {

    @Select("SELECT COUNT(*) FROM dag_alert_history WHERE execution_id = #{executionId} " +
            "AND COALESCE(node_id, '') = COALESCE(#{nodeId}, '') AND alert_type = #{alertType}")
    long countByExecutionAndType(@Param("executionId") Long executionId,
                                 @Param("nodeId") String nodeId,
                                 @Param("alertType") String alertType);
}
