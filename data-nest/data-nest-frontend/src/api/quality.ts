import request from './request';
import type {
    QualityRule,
    QualityRuleBatchCreateRequest,
    QualityRuleCreateRequest,
    QualityRuleTemplate,
    QualityRuleTemplateCreateRequest,
    QualityRuleTemplateQueryParams,
    QualityRuleUpdateRequest,
    QualityJob,
    QualityJobCreateRequest,
    QualityJobQueryParams,
    QualityJobUpdateRequest,
} from '../types/quality';
import type {PageResult, Result} from '../types/common';

/** 模板列表（含内置，仅启用；可按类型过滤）。供「批量应用」下拉选择等 */
export function listQualityTemplates(type?: string) {
    return request.get<Result<QualityRuleTemplate[]>>('/governance/quality/templates', {
        params: type ? {type} : undefined,
    });
}

/** 分页列表 */
export function queryQualityTemplates(params: QualityRuleTemplateQueryParams) {
    return request.post<Result<PageResult<QualityRuleTemplate>>>('/governance/quality/templates/page', params);
}

/** 新增自定义模板 */
export function createQualityTemplate(data: QualityRuleTemplateCreateRequest) {
    return request.post<Result<QualityRuleTemplate>>('/governance/quality/templates', data);
}

/** 编辑模板（内置/自定义均可编辑） */
export function updateQualityTemplate(id: string, data: QualityRuleTemplateCreateRequest) {
    return request.put<Result<QualityRuleTemplate>>(`/governance/quality/templates/${id}`, data);
}

/** 删除自定义模板（内置模板不可删除） */
export function deleteQualityTemplate(id: string) {
    return request.delete<Result<null>>(`/governance/quality/templates/${id}`);
}

/** 启停模板（enabled 为空时后端取反） */
export function toggleQualityTemplate(id: string, enabled?: number) {
    return request.post<Result<QualityRuleTemplate>>(`/governance/quality/templates/${id}/toggle`, undefined, {
        params: enabled != null ? {enabled} : undefined,
    });
}

// ============ 质量任务 ============

/** 分页查询质量任务 */
export function queryQualityJobs(params: QualityJobQueryParams) {
    return request.post<Result<PageResult<QualityJob>>>('/governance/quality/jobs/page', params);
}

/** 质量任务详情（含 rules 规则列表） */
export function getQualityJob(id: string) {
    return request.get<Result<QualityJob>>(`/governance/quality/jobs/${id}`);
}

/** 新增质量任务 */
export function createQualityJob(data: QualityJobCreateRequest) {
    return request.post<Result<QualityJob>>('/governance/quality/jobs', data);
}

/** 编辑质量任务 */
export function updateQualityJob(id: string, data: QualityJobUpdateRequest) {
    return request.put<Result<QualityJob>>(`/governance/quality/jobs/${id}`, data);
}

/** 删除质量任务（级联删除其规则） */
export function deleteQualityJob(id: string) {
    return request.delete<Result<null>>(`/governance/quality/jobs/${id}`);
}

/** 启停质量任务（enabled 为空时后端取反） */
export function toggleQualityJob(id: string, enabled?: number) {
    return request.post<Result<QualityJob>>(`/governance/quality/jobs/${id}/toggle`, undefined, {
        params: enabled != null ? {enabled} : undefined,
    });
}

/** 立即执行质量任务（后端当前为预留实现） */
export function executeQualityJob(id: string) {
    return request.post<Result<null>>(`/governance/quality/jobs/${id}/execute`);
}

// ============ 质量规则 ============

/** 按任务查询规则 */
export function listQualityRulesByJob(jobId: string) {
    return request.get<Result<QualityRule[]>>(`/governance/quality/rules/by-job/${jobId}`);
}

/** 规则详情 */
export function getQualityRule(id: string) {
    return request.get<Result<QualityRule>>(`/governance/quality/rules/${id}`);
}

/** 预览规则 SQL（返回模板展开后的执行 SQL 文本） */
export function previewQualityRuleSql(id: string) {
    return request.get<Result<string>>(`/governance/quality/rules/${id}/preview-sql`);
}

/** 新增质量规则 */
export function createQualityRule(data: QualityRuleCreateRequest) {
    return request.post<Result<QualityRule>>('/governance/quality/rules', data);
}

/** 编辑质量规则 */
export function updateQualityRule(id: string, data: QualityRuleUpdateRequest) {
    return request.put<Result<QualityRule>>(`/governance/quality/rules/${id}`, data);
}

/** 删除质量规则 */
export function deleteQualityRule(id: string) {
    return request.delete<Result<null>>(`/governance/quality/rules/${id}`);
}

/** 启停质量规则（enabled 为空时后端取反） */
export function toggleQualityRule(id: string, enabled?: number) {
    return request.post<Result<QualityRule>>(`/governance/quality/rules/${id}/toggle`, undefined, {
        params: enabled != null ? {enabled} : undefined,
    });
}

/** 立即执行质量规则（后端当前为预留实现） */
export function executeQualityRule(id: string) {
    return request.post<Result<null>>(`/governance/quality/rules/${id}/execute`);
}

/** 模板批量应用（1 模板 + 多表） */
export function batchCreateQualityRules(data: QualityRuleBatchCreateRequest) {
    return request.post<Result<number>>('/governance/quality/rules/batch', data);
}
