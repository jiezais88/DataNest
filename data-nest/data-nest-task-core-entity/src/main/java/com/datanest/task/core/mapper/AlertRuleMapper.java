package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.task.core.entity.AlertRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {

    /**
     * 告警规则分页查询，支持按对象类型与对象名模糊搜索。
     * 多对象时通过 alert_rule_object 关联搜索。
     */
    @Select("<script>" +
            "SELECT ar.* FROM alert_rule ar " +
            "LEFT JOIN alert_rule_object aro ON ar.id = aro.alert_rule_id " +
            "<where>" +
            "  <if test='objectType != null and objectType != \"\"'> AND ar.object_type = #{objectType} </if>" +
            "  <if test='keyword != null and keyword != \"\"'> " +
            "    AND (ar.object_name ILIKE CONCAT('%', #{keyword}, '%') OR aro.object_name ILIKE CONCAT('%', #{keyword}, '%')) " +
            "  </if>" +
            "</where>" +
            " GROUP BY ar.id " +
            " ORDER BY ar.created_at DESC" +
            "</script>")
    IPage<AlertRule> selectRulePage(Page<AlertRule> page,
                                    @Param("objectType") String objectType,
                                    @Param("keyword") String keyword);
}
