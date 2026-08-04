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
    /** 数据源范围（可选），为空表示不限数据源 */
    datasourceId?: string;
    datasourceName?: string;
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
}

/** 新增质量任务请求 */
export interface QualityJobCreateRequest {
    name: string;
    description?: string;
    datasourceId?: string;
    enabled?: number;
    scheduledEnabled?: number;
    cron?: string;
    autoTriggerEnabled?: number;
    autoTriggerObjectType?: AutoTriggerObjectType;
    autoTriggerObjectId?: string;
    alertLevel?: QualityAlertLevel;
}

/** 编辑质量任务请求 */
export interface QualityJobUpdateRequest {
    name?: string;
    description?: string;
    datasourceId?: string;
    enabled?: number;
    scheduledEnabled?: number;
    cron?: string;
    autoTriggerEnabled?: number;
    autoTriggerObjectType?: AutoTriggerObjectType;
    autoTriggerObjectId?: string;
    alertLevel?: QualityAlertLevel;
}

/** 分页查询质量任务 */
export interface QualityJobQueryParams {
    keyword?: string;
    datasourceId?: string;
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
    jobId: string;
    /** 来源模板（可选） */
    templateId?: string;
    templateName?: string;
    name: string;
    type: QualityRuleType;
    /** 对象表 ID（metadata 表） */
    tableId?: string;
    /** 对象表名（schema.table，computed 字段） */
    tableName?: string;
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
    jobId: string;
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
