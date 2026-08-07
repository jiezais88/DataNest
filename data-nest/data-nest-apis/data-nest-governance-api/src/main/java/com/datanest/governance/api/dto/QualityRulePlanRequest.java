package com.datanest.governance.api.dto;

import lombok.Data;

/**
 * 质量执行计划请求（按单规则，executeRule 路径）。
 */
@Data
public class QualityRulePlanRequest {

    /** 质量规则 ID */
    private Long ruleId;
}
