package com.datanest.task.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datanest.task.core.entity.AlertRule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {

    /**
     * 告警规则分页查询，支持按对象类型与对象名模糊搜索。
     */
    @Select("<script>" +
            "SELECT * FROM alert_rule " +
            "<where>" +
            "  <if test='objectType != null and objectType != \"\"'> AND object_type = #{objectType} </if>" +
            "  <if test='keyword != null and keyword != \"\"'> AND object_name ILIKE CONCAT('%', #{keyword}, '%') </if>" +
            "</where>" +
            " ORDER BY created_at DESC" +
            "</script>")
    IPage<AlertRule> selectRulePage(Page<AlertRule> page,
                                    @Param("objectType") String objectType,
                                    @Param("keyword") String keyword);

    /**
     * 按对象删除告警规则（删除 DAG/同步任务/采集任务时级联清理）。
     */
    @Delete("DELETE FROM alert_rule WHERE object_type = #{objectType} AND object_id = #{objectId}")
    int deleteByObject(@Param("objectType") String objectType, @Param("objectId") Long objectId);
}
