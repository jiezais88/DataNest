package com.datanest.task.core.dto;

import lombok.Data;

/**
 * 质量评分全局配置 DTO（Sprint 6 NG8「扣分配置」弹窗）。
 */
@Data
public class QualityScoreConfigDTO {

    /** 警告规则每权重扣分分值 */
    private Integer warningDeduct;

    /** 严重规则每权重扣分分值 */
    private Integer severeDeduct;

    /** 低分区阈值：评分 < 此值 → 健康度「差」；存在严重规则强制压至低分区 */
    private Integer badThreshold;
}
