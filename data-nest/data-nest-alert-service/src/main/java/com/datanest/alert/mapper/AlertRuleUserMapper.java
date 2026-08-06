package com.datanest.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.alert.entity.AlertRuleUser;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AlertRuleUserMapper extends BaseMapper<AlertRuleUser> {

    @Select("SELECT user_id FROM alert_rule_user WHERE alert_rule_id = #{alertRuleId}")
    List<Long> selectUserIdsByRuleId(@Param("alertRuleId") Long alertRuleId);

    /**
     * 批量查询多规则的所有接收用户关联（避免列表页 N+1）。
     */
    @Select("<script>" +
            "SELECT alert_rule_id, user_id FROM alert_rule_user WHERE alert_rule_id IN " +
            "<foreach collection='ruleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<AlertRuleUser> selectByRuleIds(@Param("ruleIds") List<Long> ruleIds);

    @Delete("DELETE FROM alert_rule_user WHERE alert_rule_id = #{alertRuleId}")
    int deleteByRuleId(@Param("alertRuleId") Long alertRuleId);

    @Insert("<script>" +
            "INSERT INTO alert_rule_user (id, alert_rule_id, user_id) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.alertRuleId}, #{item.userId})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<AlertRuleUser> list);
}
