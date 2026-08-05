/**
 * 数据质量模块类型定义（单一出处）。
 * Sprint 6 规则模板库，对齐后端 com.datanest.task.core.dto.QualityRuleTemplateDTO。
 * Sprint 6 质量任务 + 质量规则（配置层），对齐后端 QualityJob* / QualityRule* DTO。
 */

/** 模板类型：完整性 / 唯一性 / 值域范围 / 自定义 SQL */
export type QualityTemplateType = 'COMPLETENESS' | 'UNIQUENESS' | 'RANGE' | 'CUSTOM_SQL';

/** 质量规则模板（列表 / 详情响应） */
export interface QualityRuleTemplate {
    id: string;
    name: string;
    type: QualityTemplateType;
    description?: string;
    /** 校验 SQL 模板，占位符 {table}/{column}/{min}/{max} 等 */
    sqlTemplate?: string;
    /** 结果指标名，如 null_rate / duplicate_count / out_of_range_rate */
    resultMetric?: string;
    /** 是否内置：1 内置，0 自定义 */
    builtin: number;
    /** 是否启用：1 启用，0 停用 */
    enabled: number;
    createdByName?: string;
    updatedByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** 新增自定义模板请求 */
export interface QualityRuleTemplateCreateRequest {
    name: string;
    type: QualityTemplateType;
    description?: string;
    sqlTemplate?: string;
    resultMetric?: string;
    enabled?: number;
}

/** 编辑模板请求（内置/自定义均可编辑） */
export type QualityRuleTemplateUpdateRequest = QualityRuleTemplateCreateRequest;

/** 分页查询请求 */
export interface QualityRuleTemplateQueryParams {
    keyword?: string;
    type?: QualityTemplateType | '';
    builtin?: number;
    enabled?: number;
    page: number;
    pageSize: number;
}

// ============ 质量任务 ============

/** 告警触发等级：仅严重 / 严重+警告 */
export type QualityAlertLevel = 'SEVERE_ONLY' | 'SEVERE_WARNING';

/** 自动触发绑定对象类型：DAG 节点 / 同步任务 / 采集任务 */
export type AutoTriggerObjectType = 'DAG_NODE' | 'SYNC_JOB' | 'COLLECT_TASK';

/** 质量任务（列表 / 详情响应） */
export interface QualityJob {
    id: string;
    name: string;
    description?: string;
    /** 是否启用：1 启用，0 停用 */
    enabled: number;
    /** 是否定时调度：1 是，0 否 */
    scheduledEnabled: number;
    /** Cron 表达式（scheduledEnabled=1 时必填） */
    cron?: string;
    /** 任务完成自动触发：1 开，0 关 */
    autoTriggerEnabled: number;
    /** 自动触发对象类型 */
    autoTriggerObjectType?: AutoTriggerObjectType;
    /** 自动触发对象 ID（DAG 节点主键 / 同步任务主键 / 采集任务主键） */
    autoTriggerObjectId?: string;
    /** 告警触发等级 */
    alertLevel: QualityAlertLevel;
    /** 规则数量（列表 computed 字段） */
    ruleCount?: number;
    /** 调度状态徽章（后端 computed：已启用 / 已停用 / —） */
    scheduleStatusBadge?: string;
    /** 最近自动触发时间 */
    lastTriggerAt?: string;
    createdByName?: string;
    updatedByName?: string;
    createdAt?: string;
    updatedAt?: string;
    /** 详情接口返回的任务下规则列表 */
    rules?: QualityRule[];
    /** 任务引用的规则 ID 集合（详情返回，供前端编辑回显，Sprint 7） */
    ruleIds?: string[];
}

/** 新增质量任务请求 */
export interface QualityJobCreateRequest {
    name: string;
    description?: string;
    enabled?: number;
    scheduledEnabled?: number;
    cron?: string;
    autoTriggerEnabled?: number;
    autoTriggerObjectType?: AutoTriggerObjectType;
    autoTriggerObjectId?: string;
    alertLevel?: QualityAlertLevel;
    /** 引用的质量规则 ID 集合（Sprint 7） */
    ruleIds?: string[];
}

/** 编辑质量任务请求 */
export interface QualityJobUpdateRequest {
    name?: string;
    description?: string;
    enabled?: number;
    scheduledEnabled?: number;
    cron?: string;
    autoTriggerEnabled?: number;
    autoTriggerObjectType?: AutoTriggerObjectType;
    autoTriggerObjectId?: string;
    alertLevel?: QualityAlertLevel;
    /** 引用的质量规则 ID 集合（全量覆盖，Sprint 7） */
    ruleIds?: string[];
}

/** 分页查询质量任务 */
export interface QualityJobQueryParams {
    keyword?: string;
    enabled?: number;
    /** 仅筛选已启用定时调度 */
    scheduledEnabled?: number;
    page: number;
    pageSize: number;
}

// ============ 质量规则 ============

/** 质量规则类型 */
export type QualityRuleType = QualityTemplateType;

/** 质量规则/模板类型的统一中文展示（单一出处，列表/表单/批量应用共用） */
export const QUALITY_TYPE_LABEL: Record<QualityRuleType, string> = {
    COMPLETENESS: '完整性',
    UNIQUENESS: '唯一性',
    RANGE: '值域范围',
    CUSTOM_SQL: '自定义 SQL',
};

/** 质量规则/模板类型下拉选项（单一出处） */
export const QUALITY_TYPE_OPTIONS: {value: QualityRuleType; label: string}[] = [
    {value: 'COMPLETENESS', label: QUALITY_TYPE_LABEL.COMPLETENESS},
    {value: 'UNIQUENESS', label: QUALITY_TYPE_LABEL.UNIQUENESS},
    {value: 'RANGE', label: QUALITY_TYPE_LABEL.RANGE},
    {value: 'CUSTOM_SQL', label: QUALITY_TYPE_LABEL.CUSTOM_SQL},
];

/** 规则结果校验指标：如 null_rate / duplicate_count / out_of_range_rate */
export type QualityResultMetric = string;

/** 质量规则（列表 / 详情响应） */
export interface QualityRule {
    id: string;
    /** 所属质量任务（历史兼容字段，可空） */
    jobId?: string;
    /** 所属任务名（经关联表回填，可多任务引用，Sprint 7） */
    jobName?: string;
    /** 来源模板（可选） */
    templateId?: string;
    templateName?: string;
    name: string;
    type: QualityRuleType;
    /** 对象表 ID（metadata 表） */
    tableId?: string;
    /** 对象表名（schema.table，computed 字段） */
    tableName?: string;
    /** 对象表归属数据库名（经 metadata_table 回填，编辑级联回显数据库下拉） */
    databaseName?: string;
    /** 对象表归属 Schema 名（经 metadata_table 回填，有 schema 类型才有值） */
    schemaName?: string;
    /** 对象表归属数据源 ID（经 metadata_table 回填） */
    datasourceId?: string;
    /** 对象表归属数据源名（内置 Doris 显示 "Doris 数仓"） */
    datasourceName?: string;
    /** 检查字段（唯一性/值域必填；完整性可空） */
    columnName?: string;
    /** 是否按字段检查：1 按字段，0 整表（完整性用） */
    checkField?: number;
    /** 实际校验 SQL（CUSTOM_SQL 时落库；模板类为空） */
    sqlExpression?: string;
    /** 警告阈值（RANGE 时为值域下限 {min}） */
    warningThreshold?: number;
    /** 严重阈值（RANGE 时为值域上限 {max}） */
    severeThreshold?: number;
    /** 结果指标 */
    resultMetric?: string;
    /** 权重 */
    weight?: number;
    /** 是否启用：1 启用，0 停用 */
    enabled: number;
    createdByName?: string;
    updatedByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** 新增质量规则请求 */
export interface QualityRuleCreateRequest {
    /** 所属质量任务（可选，规则可独立创建；任务引用经关联表，Sprint 7） */
    jobId?: string;
    templateId?: string;
    name: string;
    type: QualityRuleType;
    tableId?: string;
    columnName?: string;
    checkField?: number;
    sqlExpression?: string;
    warningThreshold?: number;
    severeThreshold?: number;
    resultMetric?: string;
    weight?: number;
    enabled?: number;
}

/** 编辑质量规则请求 */
export interface QualityRuleUpdateRequest {
    templateId?: string;
    name?: string;
    type?: QualityRuleType;
    tableId?: string;
    columnName?: string;
    checkField?: number;
    sqlExpression?: string;
    warningThreshold?: number;
    severeThreshold?: number;
    resultMetric?: string;
    weight?: number;
    enabled?: number;
}

/** 模板批量应用：单条明细项 */
export interface RuleBatchItem {
    tableId: string;
    tableName?: string;
    columnName?: string;
    checkField?: number;
    name?: string;
    sqlExpression?: string;
    warningThreshold?: number;
    severeThreshold?: number;
    weight?: number;
}

/** 模板批量应用请求 */
export interface QualityRuleBatchCreateRequest {
    jobId: string;
    templateId: string;
    items: RuleBatchItem[];
}

/** 质量规则分页查询请求（Sprint 7 规则独立菜单） */
export interface QualityRuleQueryParams {
    keyword?: string;
    type?: QualityRuleType | '';
    enabled?: number;
    /** 所属任务过滤 */
    jobId?: string;
    page: number;
    pageSize: number;
}

// ============ 质量检查执行历史（Sprint 8 执行层） ============

/** 批次触发方式：手动 / 定时 / 自动触发 */
export type QualityCheckTriggerType = 'MANUAL' | 'SCHEDULED' | 'AUTO_TRIGGER';

/** 批次状态：运行中 / 成功 / 部分失败 / 失败 */
export type QualityCheckStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL_FAILED' | 'FAILED';

/** 规则分级判定（对齐后端 QualityCheckDetail.resultLevel）：通过 / 警告 / 严重 / 不可用 */
export type QualityCheckLevel = 'PASS' | 'WARNING' | 'SEVERE' | 'UNAVAILABLE';

/** 质量检查批次（对齐后端 QualityCheckBatchDTO） */
export interface QualityCheckBatch {
    id: string;
    /** 所属质量任务 ID（单规则执行为空） */
    jobId?: string;
    /** 任务名；单规则执行时显示"单规则执行" */
    jobName?: string;
    /** 触发方式 */
    triggerType?: QualityCheckTriggerType;
    /** 批次状态 */
    status?: QualityCheckStatus;
    startedAt?: string;
    endedAt?: string;
    /** 耗时（毫秒） */
    durationMs?: number;
    errorMessage?: string;
    /** 规则总数 */
    ruleCount?: number;
    /** 成功规则数 */
    successCount?: number;
    /** 失败规则数 */
    failedCount?: number;
    /** 规则明细（仅详情接口回填） */
    details?: QualityCheckDetail[];
    createdAt?: string;
}

/** 质量检查规则明细（对齐后端 QualityCheckDetailDTO） */
export interface QualityCheckDetail {
    id: string;
    batchId?: string;
    ruleId?: string;
    ruleName?: string;
    /** 规则类型（COMPLETENESS/UNIQUENESS/RANGE/CUSTOM_SQL） */
    ruleType?: QualityRuleType;
    tableId?: string;
    /** 目标表名（schema.table） */
    tableName?: string;
    /** 结果指标，如 null_rate / duplicate_count */
    resultMetric?: string;
    /** 结果值 */
    resultValue?: number | string;
    /** 分级判定：通过 / 警告 / 严重 / 不可用（Sprint 6 分级告警） */
    resultLevel?: QualityCheckLevel;
    /** 1 成功，0 失败 */
    success?: number;
    errorMessage?: string;
    /** 实际执行 SQL */
    executedSql?: string;
    createdAt?: string;
}

/** 批次分页查询请求（对齐后端 QualityCheckQueryRequest） */
export interface QualityCheckQueryParams {
    page: number;
    pageSize: number;
    /** 质量任务 ID 过滤 */
    jobId?: string;
    /** 触发方式过滤 */
    triggerType?: QualityCheckTriggerType | '';
    /** 批次状态过滤 */
    status?: QualityCheckStatus | '';
}

/** 批次触发方式中文展示（单一出处） */
export const QUALITY_CHECK_TRIGGER_LABEL: Record<QualityCheckTriggerType, string> = {
    MANUAL: '手动触发',
    SCHEDULED: '定时触发',
    AUTO_TRIGGER: '自动触发',
};

/** 批次状态中文展示 + 徽章变体（单一出处） */
export const QUALITY_CHECK_STATUS_LABEL: Record<QualityCheckStatus, string> = {
    RUNNING: '运行中',
    SUCCESS: '成功',
    PARTIAL_FAILED: '部分失败',
    FAILED: '失败',
};

/** 规则分级中文展示（对齐后端 AlertConstants.QUALITY_LEVEL_*） */
export const QUALITY_CHECK_LEVEL_LABEL: Record<QualityCheckLevel, string> = {
    PASS: '通过',
    WARNING: '警告',
    SEVERE: '严重',
    UNAVAILABLE: '不可用',
};

// ============ 表级质量评分（Sprint 6 NG8） ============

/** 健康度等级（对齐后端 QualityScoreConstants）：优秀 / 良好 / 一般 / 差 */
export type QualityHealthLevel = 'EXCELLENT' | 'GOOD' | 'WARNING' | 'BAD';

/** 健康度中文展示（单一出处，评分列表/详情/血缘徽章共用） */
export const QUALITY_HEALTH_LABEL: Record<QualityHealthLevel, string> = {
    EXCELLENT: '优秀',
    GOOD: '良好',
    WARNING: '一般',
    BAD: '差',
};

/** 健康度下拉选项（评分列表页筛选） */
export const QUALITY_HEALTH_OPTIONS: {value: QualityHealthLevel | ''; label: string}[] = [
    {value: '', label: '全部健康度'},
    {value: 'EXCELLENT', label: '优秀'},
    {value: 'GOOD', label: '良好'},
    {value: 'WARNING', label: '一般'},
    {value: 'BAD', label: '差'},
];

/** 表级质量评分（对齐后端 QualityScoreDTO） */
export interface QualityScore {
    id?: string;
    /** 目标表 metadata_table.id */
    tableId?: string;
    /** 库名.表名 */
    tableName?: string;
    datasourceId?: string;
    datasourceName?: string;
    /** 0-100 分 */
    score?: number | string;
    healthLevel?: QualityHealthLevel;
    healthLevelLabel?: string;
    /** 最近一次通过规则数 */
    passRules?: number;
    /** 最近一次警告规则数 */
    warningRules?: number;
    /** 最近一次严重规则数 */
    severeRules?: number;
    lastCheckedAt?: string;
}

/** 评分列表分页查询请求（对齐后端 QualityScoreQueryRequest） */
export interface QualityScoreQueryParams {
    page: number;
    pageSize: number;
    /** 表名关键字（库名.表名 模糊匹配） */
    keyword?: string;
    datasourceId?: string;
    healthLevel?: QualityHealthLevel | '';
}

/** 单表规则 + 最近一次检查结果（对齐后端 QualityTableRuleResultDTO，元数据「质量」页签） */
export interface QualityTableRuleResult {
    ruleId?: string;
    ruleName?: string;
    ruleType?: QualityRuleType;
    /** 所属任务名（可多任务引用，逗号拼接） */
    jobName?: string;
    /** 检查字段 */
    columnName?: string;
    weight?: number;
    /** 最近一次结果值 */
    resultValue?: number | string;
    /** 最近一次分级：通过/警告/严重/不可用 */
    resultLevel?: QualityCheckLevel;
    /** 最近一次检查时间 */
    lastCheckedAt?: string;
    /** 最近一次执行是否成功：1 成功，0 失败 */
    success?: number;
}

/** 质量评分全局配置（对齐后端 QualityScoreConfigDTO，扣分配置弹窗） */
export interface QualityScoreConfig {
    /** 警告规则每权重扣分分值 */
    warningDeduct?: number;
    /** 严重规则每权重扣分分值 */
    severeDeduct?: number;
    /** 低分区阈值 */
    badThreshold?: number;
}
