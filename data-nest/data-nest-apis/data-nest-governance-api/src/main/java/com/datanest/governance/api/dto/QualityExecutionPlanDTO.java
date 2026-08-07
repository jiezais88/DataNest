package com.datanest.governance.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 质量执行计划（服务端一次性装配：任务 + 启用规则 + 模板 + 元数据表 + 最终执行 SQL）。
 * <p>
 * 阈值判定（determineLevel）留在 worker 侧，服务端只透出阈值；规则空列表合法。
 */
@Data
public class QualityExecutionPlanDTO {

    /** 质量任务 ID（单规则执行可空） */
    private Long jobId;

    /** 任务名称 */
    private String jobName;

    /** 告警触发等级：SEVERE_ONLY / SEVERE_WARNING */
    private String alertLevel;

    /** 执行超时阈值（分钟），null = 不启用超时检测 */
    private Integer timeoutMinutes;

    /** 启用规则执行项列表（空列表合法） */
    private List<RulePlanItem> rules;

    /**
     * 单条规则的执行计划项。
     */
    @Data
    public static class RulePlanItem {

        /** 规则 ID */
        private Long ruleId;

        /** 规则名称 */
        private String ruleName;

        /** 目标表 metadata_table.id */
        private Long tableId;

        /** 目标表名 */
        private String tableName;

        /** 目标表数据源 ID（-1 = 内置 Doris） */
        private Long datasourceId;

        /** 最终执行 SQL（模板占位符已展开；生成失败为 null，由 worker 按执行失败处理） */
        private String executedSql;

        /** 警告阈值（执行结果 ≥ 此值 → 警告） */
        private BigDecimal warningThreshold;

        /** 严重阈值（执行结果 ≥ 此值 → 严重） */
        private BigDecimal severeThreshold;

        /** 阈值比较符（规则实体暂无该字段，预留透出，当前恒为 null） */
        private String comparator;

        /** 规则类型：COMPLETENESS / UNIQUENESS / RANGE / CUSTOM_SQL */
        private String ruleType;

        /** 结果值提取列名（worker 按该列名从查询结果取值，取不到降级首行首列） */
        private String resultMetric;
    }
}
