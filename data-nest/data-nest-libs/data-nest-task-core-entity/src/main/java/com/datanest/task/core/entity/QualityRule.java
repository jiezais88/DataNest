package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量规则实例（Sprint 6 配置层，D-D3，挂任务下）。
 * <p>
 * 规则 = 来源模板 + 表 + 字段 + 阈值 + 权重 的具体化实例。任务内「选择模板 + 多表」批量生成。
 * {@code sql_expression} 执行时动态生成（由模板 SQL + 占位符替换），本次配置层不落库；自定义 SQL 除外。
 */
@Data
@TableName("quality_rule")
public class QualityRule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属质量任务 */
    private Long jobId;

    /** 来源模板（可空，自定义 SQL 也记） */
    private Long templateId;

    /** 规则名称 */
    private String name;

    /** 规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
    private String type;

    /** 目标表 metadata_table.id */
    private Long tableId;

    /** 检查字段（唯一性/值域必填；完整性可空） */
    private String columnName;

    /** 是否按字段检查（完整性填字段时=1，整表=0） */
    private Integer checkField;

    /** 实际校验 SQL（执行时动态生成，本次不落库；自定义 SQL 除外） */
    private String sqlExpression;

    /** 警告阈值（执行结果 ≥ 此值 → 警告） */
    private BigDecimal warningThreshold;

    /** 严重阈值（执行结果 ≥ 此值 → 严重） */
    private BigDecimal severeThreshold;

    /** 值域下界（RANGE 类型专用，SQL 模板 {min} 来源；其余类型为 NULL） */
    private BigDecimal rangeMin;

    /** 值域上界（RANGE 类型专用，SQL 模板 {max} 来源；其余类型为 NULL） */
    private BigDecimal rangeMax;

    /** 结果指标名 */
    private String resultMetric;

    /** 权重（评分加权，默认 1） */
    private Integer weight;

    /** 规则启用状态：1 启用，0 停用 */
    private Integer enabled;

    private Long createdBy;

    private Long updatedBy;

    @TableField(exist = false)
    private String createdByName;

    @TableField(exist = false)
    private String updatedByName;

    @TableField(exist = false)
    private String tableName;

    @TableField(exist = false)
    private String templateName;

    @TableField(exist = false)
    private String datasourceName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
