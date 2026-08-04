package com.datanest.task.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量检查规则明细（Sprint 8 执行层）。
 * <p>
 * 批次下每条规则的执行结果：结果值、是否成功、执行 SQL、错误信息。
 * 本次仅记录结果值（result_value），不做分级判定。
 */
@Data
@TableName("quality_check_detail")
public class QualityCheckDetail {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属批次 */
    private Long batchId;

    /** 质量规则 ID */
    private Long ruleId;

    /** 规则名称快照 */
    private String ruleName;

    /** 规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
    private String ruleType;

    /** 目标表 metadata_table.id */
    private Long tableId;

    /** 结果指标名 */
    private String resultMetric;

    /** 执行结果值（DECIMAL） */
    private BigDecimal resultValue;

    /** 规则执行是否成功：1 成功，0 失败 */
    private Integer success;

    /** 规则执行错误信息 */
    private String errorMessage;

    /** 实际执行的校验 SQL */
    private String executedSql;

    private LocalDateTime createdAt;
}
