package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量规则模板（Sprint 6 规则模板库，D-D3 决策）
 * 对应表 quality_rule_template
 * <p>
 * 模板 = 校验逻辑模板（类型 + SQL 片段 + 字段/阈值占位符），任务内「选择模板 + 多表」
 * 批量生成 {@code quality_rule} 实例，避免重复配置。内置四类模板 + 治理员可维护自定义模板。
 */
@Data
@TableName("quality_rule_template")
public class QualityRuleTemplate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 模板名称（唯一） */
    private String name;

    /** 模板类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
    private String type;

    /** 模板说明 */
    private String description;

    /** 校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等；自定义 SQL 可为空（由用户填写） */
    private String sqlTemplate;

    /** 结果指标名，如 null_rate / duplicate_count / out_of_range_rate */
    private String resultMetric;

    /** 是否内置：1 内置，0 自定义 */
    private Integer builtin;

    /** 是否启用：1 启用，0 停用 */
    private Integer enabled;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
