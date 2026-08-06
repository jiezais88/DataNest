package com.datanest.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datanest.alert.entity.AlertRuleObject;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AlertRuleObjectMapper extends BaseMapper<AlertRuleObject> {

    @Select("SELECT * FROM alert_rule_object WHERE alert_rule_id = #{alertRuleId}")
    List<AlertRuleObject> selectByRuleId(@Param("alertRuleId") Long alertRuleId);

    @Select("SELECT * FROM alert_rule_object WHERE object_type = #{objectType} AND object_id = #{objectId}")
    List<AlertRuleObject> selectByObject(@Param("objectType") String objectType, @Param("objectId") Long objectId);

    @Delete("DELETE FROM alert_rule_object WHERE alert_rule_id = #{alertRuleId}")
    int deleteByRuleId(@Param("alertRuleId") Long alertRuleId);

    @Delete("DELETE FROM alert_rule_object WHERE object_type = #{objectType} AND object_id = #{objectId}")
    int deleteByObject(@Param("objectType") String objectType, @Param("objectId") Long objectId);

    @Insert("<script>" +
            "INSERT INTO alert_rule_object (id, alert_rule_id, object_type, object_id, object_name, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.id}, #{item.alertRuleId}, #{item.objectType}, #{item.objectId}, #{item.objectName}, #{item.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<AlertRuleObject> list);
}
