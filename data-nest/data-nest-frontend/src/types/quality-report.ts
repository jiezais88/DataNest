// Sprint 8 F3：质量报告类型（对齐后端 governance QualityReportController DTO）
// 注意：计数/ID 字段后端 Long 序列化为 string；avgScore/passRate 为 BigDecimal（JSON number）。

/** 统一筛选请求（数据源/库/质量任务/时间范围 + 评分趋势单表模式 + 问题清单分页） */
export interface QualityReportRequest {
    /** 数据源 ID（-1 = 内置 Doris） */
    datasourceId?: string;
    /** 库名（随数据源联动） */
    databaseName?: string;
    jobId?: string;
    /** ISO 8601；空默认最近 30 天 / 当前时间 */
    startTime?: string;
    endTime?: string;
    /** 表 ID（可选；传了走单表模式，空 = 按天聚合平均评分） */
    tableId?: string;
    page?: number;
    pageSize?: number;
}

/** 筛选联动选项 */
export interface QualityReportOptions {
    datasources?: { id: string; name?: string }[];
    /** 库（带所属数据源，供选库反向联动数据源） */
    databases?: { name: string; datasourceId?: string }[];
    /** 质量任务（随数据源联动） */
    jobs?: { id: string; name?: string }[];
}

/** KPI 汇总（计数为 Long→string；avgScore/passRate 缺省 = 无数据） */
export interface QualityReportSummary {
    batchCount?: string;
    detailCount?: string;
    avgScore?: number;
    passRate?: number;
    severeCount?: string;
    warningCount?: string;
}

/** 四档分布趋势点（按天；计数为 Long→string） */
export interface QualityLevelTrendPoint {
    day: string;
    passCount?: string;
    warningCount?: string;
    severeCount?: string;
    unavailableCount?: string;
}

/** 评分趋势点（聚合模式：day + avgScore + tableCount；单表模式：checkedAt + score + healthLevel） */
export interface QualityScoreTrendPoint {
    /** 单表模式：批次结束时间 */
    checkedAt?: string;
    /** 单表模式：0-100 评分 */
    score?: number;
    /** 单表模式：健康度 */
    healthLevel?: string;
    /** 聚合模式：日期 yyyy-MM-dd */
    day?: string;
    /** 聚合模式：当天平均评分 */
    avgScore?: number;
    /** 聚合模式：当天有快照的表数（Long→string） */
    tableCount?: string;
}

/** 表评分分布（环图；计数为 Long→string） */
export interface QualityScoreDistribution {
    excellentCount?: string;
    goodCount?: string;
    warningCount?: string;
    badCount?: string;
    noScoreCount?: string;
    totalTables?: string;
}

/** 数据源质量对比项 */
export interface DatasourceScoreComparison {
    datasourceId: string;
    datasourceName?: string;
    avgScore?: number;
    tableCount?: string;
}

/** 问题清单项（SEVERE/WARNING 规则明细） */
export interface QualityIssueItem {
    detailId: string;
    tableId: string;
    /** 库名.表名 */
    tableName?: string;
    ruleId: string;
    ruleName?: string;
    /** COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL/PYTHON */
    ruleType?: string;
    resultMetric?: string;
    resultValue?: number;
    /** WARNING 取警告阈值、SEVERE 取严重阈值；规则已删缺省 */
    threshold?: number;
    resultLevel?: string;
    checkedAt?: string;
}
