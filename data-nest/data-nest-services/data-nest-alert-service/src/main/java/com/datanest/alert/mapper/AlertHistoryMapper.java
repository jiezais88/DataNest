package com.datanest.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.alert.dto.AlertHistoryStatsDTO;
import com.datanest.alert.entity.AlertHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AlertHistoryMapper extends BaseMapper<AlertHistory> {

    /**
     * 按质量检查批次 ID 查询告警历史（供治理服务批次详情反查）。
     */
    @Select("SELECT * FROM alert_history WHERE quality_batch_id = #{batchId} ORDER BY sent_at DESC")
    List<AlertHistory> selectByQualityBatchId(@Param("batchId") Long batchId);

    /**
     * 清理发送时间早于指定时间点的告警历史，返回删除条数。
     */
    @Delete("DELETE FROM alert_history WHERE sent_at < #{before}")
    int deleteSentBefore(@Param("before") LocalDateTime before);

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
     * 微服务化 5.0：dag / sync_job / collect_task / quality_job 均属外域，跨库 JOIN 已移除，
     * objectName 由调用方（AlertRuleService.listHistory）经 Feign 按 objectType 分组批量回填；
     * ruleName 仍走同域 alert_rule JOIN（冗余 rule_name 列兜底）。
     */
    @Select("<script>" +
            "SELECT ah.*, COALESCE(ar.name, ah.rule_name) AS ruleName " +
            "FROM alert_history ah " +
            "LEFT JOIN alert_rule ar ON ah.alert_rule_id = ar.id " +
            "<where>" +
            "  <if test='objectType != null and objectType != \"\"'> AND ah.object_type = #{objectType} </if>" +
            "  <if test='objectId != null'> AND ah.object_id = #{objectId} </if>" +
            "  <if test='alertType != null and alertType != \"\"'> AND ah.alert_type = #{alertType} </if>" +
            "  <if test='sendStatus != null and sendStatus != \"\"'> AND ah.send_status = #{sendStatus} </if>" +
            "  <if test='sentAtFrom != null'> AND ah.sent_at &gt;= #{sentAtFrom} </if>" +
            "  <if test='sentAtTo != null'> AND ah.sent_at &lt;= #{sentAtTo} </if>" +
            "</where>" +
            " ORDER BY ah.sent_at DESC" +
            "</script>")
    IPage<AlertHistory> selectHistoryPage(Page<AlertHistory> page,
                                          @Param("objectType") String objectType,
                                          @Param("objectId") Long objectId,
                                          @Param("alertType") String alertType,
                                          @Param("sendStatus") String sendStatus,
                                          @Param("sentAtFrom") LocalDateTime sentAtFrom,
                                          @Param("sentAtTo") LocalDateTime sentAtTo);

    /**
     * 告警历史统计（列表页顶部统计卡）：按告警类型条件聚合 + 发送失败计数。
     * 与 selectHistoryPage 同筛选条件（时间范围 + 对象维度），避免前端拉全量列表计数。
     */
    @Select("<script>" +
            "SELECT " +
            "  COUNT(*) FILTER (WHERE alert_type = 'FAILURE') AS failure, " +
            "  COUNT(*) FILTER (WHERE alert_type = 'TIMEOUT') AS timeout, " +
            "  COUNT(*) FILTER (WHERE alert_type = 'LAG_EXCEEDED') AS lag_exceeded, " +
            "  COUNT(*) FILTER (WHERE alert_type = 'EXTERNAL_STOP') AS external_stop, " +
            "  COUNT(*) FILTER (WHERE alert_type = 'SUCCESS') AS success, " +
            "  COUNT(*) FILTER (WHERE send_status = 'FAILED') AS send_failed " +
            "FROM alert_history ah " +
            "<where>" +
            "  <if test='objectType != null and objectType != \"\"'> AND ah.object_type = #{objectType} </if>" +
            "  <if test='objectId != null'> AND ah.object_id = #{objectId} </if>" +
            "  <if test='sentAtFrom != null'> AND ah.sent_at &gt;= #{sentAtFrom} </if>" +
            "  <if test='sentAtTo != null'> AND ah.sent_at &lt;= #{sentAtTo} </if>" +
            "</where>" +
            "</script>")
    AlertHistoryStatsDTO selectHistoryStats(@Param("objectType") String objectType,
                                            @Param("objectId") Long objectId,
                                            @Param("sentAtFrom") LocalDateTime sentAtFrom,
                                            @Param("sentAtTo") LocalDateTime sentAtTo);
}
