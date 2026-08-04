/**
 * 数据质量模块类型定义（单一出处）。
 * Sprint 6 规则模板库，对齐后端 com.datanest.task.core.dto.QualityRuleTemplateDTO。
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
