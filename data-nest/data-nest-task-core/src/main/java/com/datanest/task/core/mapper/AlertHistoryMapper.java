package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.task.core.entity.AlertHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertHistoryMapper extends BaseMapper<AlertHistory> {

    /**
     * 60 秒窗口内是否已发送过同类告警（防并发终态回调重复发邮件）。
     */
    @Select("SELECT COUNT(*) FROM alert_history " +
            "WHERE object_type = #{objectType} AND object_id = #{objectId} " +
            "AND alert_type = #{alertType} AND sent_at > NOW() - INTERVAL '60 seconds'")
    long countRecent(@Param("objectType") String objectType,
                     @Param("objectId") Long objectId,
                     @Param("alertType") String alertType);

    /**
     * 告警历史分页查询。
     * 列表展示需要对象名：按 object_type 分别 LEFT JOIN dag / sync_job / collect_task，
     * 以 COALESCE 取到 object_name（冗余到查询结果，不落库）。
     */
    @Select("<script>" +
            "SELECT ah.*, COALESCE(d.name, sj.name, ct.name) AS objectName " +
            "FROM alert_history ah " +
            "LEFT JOIN dag d ON ah.object_type = 'DAG' AND d.id = ah.object_id " +
            "LEFT JOIN sync_job sj ON ah.object_type = 'SYNC_JOB' AND sj.id = ah.object_id " +
            "LEFT JOIN collect_task ct ON ah.object_type = 'COLLECT_TASK' AND ct.id = ah.object_id " +
            "<where>" +
            "  <if test='objectType != null and objectType != \"\"'> AND ah.object_type = #{objectType} </if>" +
            "  <if test='objectId != null'> AND ah.object_id = #{objectId} </if>" +
            "  <if test='alertType != null and alertType != \"\"'> AND ah.alert_type = #{alertType} </if>" +
            "  <if test='sendStatus != null and sendStatus != \"\"'> AND ah.send_status = #{sendStatus} </if>" +
            "</where>" +
            " ORDER BY ah.sent_at DESC" +
            "</script>")
    IPage<AlertHistory> selectHistoryPage(Page<AlertHistory> page,
                                          @Param("objectType") String objectType,
                                          @Param("objectId") Long objectId,
                                          @Param("alertType") String alertType,
                                          @Param("sendStatus") String sendStatus);
}
