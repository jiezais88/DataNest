package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 质量评分全局配置（Sprint 6 NG8）。
 * <p>
 * 单行配置：警告/严重每权重扣分分值 + 低分区阈值。
 * 原为 Nacos {@code @Value} 配置，改为落库以便「扣分配置」弹窗动态读写（{@code ScoreCalculator} 优先读表）。
 */
@Data
@TableName("quality_score_config")
public class QualityScoreConfig {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 警告规则每权重扣分分值 */
    private Integer warningDeduct;

    /** 严重规则每权重扣分分值 */
    private Integer severeDeduct;

    /** 低分区阈值：评分 < 此值 → 健康度「差」；存在严重规则强制压至低分区 */
    private Integer badThreshold;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}
