package com.datanest.governance.api.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 质量检查明细落库请求（单条规则执行结果）。
 */
@Data
public class QualityDetailCreateRequest {

    /** 质量规则 ID */
    private Long ruleId;

    /** 规则名称快照 */
    private String ruleName;

    /** 目标表 metadata_table.id */
    private Long tableId;

    /** 目标表名（单规则批次更新 batch.jobName 用） */
    private String tableName;

    /** 实际执行的校验 SQL */
    private String executedSql;

    /** 执行结果值 */
    private BigDecimal resultValue;

    /** 分级判定：PASS / WARNING / SEVERE / UNAVAILABLE（worker 侧按阈值判定后透传） */
    private String resultLevel;

    /** 规则执行是否成功：1 成功，0 失败 */
    private Integer success;

    /** 规则执行错误信息 */
    private String errorMessage;
}
