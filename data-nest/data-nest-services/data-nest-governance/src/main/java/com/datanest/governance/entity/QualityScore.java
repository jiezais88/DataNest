package com.datanest.governance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 表级质量评分（Sprint 6 NG8）。
 * <p>
 * 一张表一行最新评分，跨任务聚合该表所有启用规则的最近一次检查结果计算产出。
 * 供血缘图谱节点与元数据详情页批量展示，避免实时 join 检查历史导致的 N+1。
 * {@code health_level} 由 {@code ScoreCalculator} 按分数区间映射：EXCELLENT/GOOD/WARNING/BAD。
 */
@Data
@TableName("quality_score")
public class QualityScore {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 目标表 metadata_table.id */
    private Long tableId;

    /** 库名.表名 */
    private String tableName;

    /** 数据源 */
    private Long datasourceId;

    /** 0-100 分 */
    private BigDecimal score;

    /** 健康度：EXCELLENT/GOOD/WARNING/BAD */
    private String healthLevel;

    /** 最近一次通过规则数 */
    private Integer passRules;

    /** 最近一次警告规则数 */
    private Integer warningRules;

    /** 最近一次严重规则数 */
    private Integer severeRules;

    /** 最近检查时间 */
    private LocalDateTime lastCheckedAt;

    /** 评分更新时间 */
    private LocalDateTime updatedAt;
}
