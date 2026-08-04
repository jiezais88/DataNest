import request from './request';
import type {
    QualityRuleTemplate,
    QualityRuleTemplateCreateRequest,
    QualityRuleTemplateQueryParams,
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
