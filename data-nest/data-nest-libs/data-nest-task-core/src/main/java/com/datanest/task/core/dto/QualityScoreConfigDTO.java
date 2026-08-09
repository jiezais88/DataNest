package com.datanest.task.core.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "质量评分全局配置（扣分配置弹窗）")
@Data
public class QualityScoreConfigDTO {

    @Schema(description = "警告规则每权重扣分分值")
    private Integer warningDeduct;

    @Schema(description = "严重规则每权重扣分分值")
    private Integer severeDeduct;

    @Schema(description = "低分区阈值：评分 < 此值 → 健康度「差」；存在严重规则强制压至低分区")
    private Integer badThreshold;
}
