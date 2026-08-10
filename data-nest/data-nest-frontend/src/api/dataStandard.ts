import request from './request';
import type {
    ComplianceCheckPageParams,
    ComplianceCheckParams,
    ComplianceCheckResult,
    ComplianceCheckSummary,
    FieldTypeStandard,
    FieldTypeStandardCreateRequest,
    FieldTypeStandardQueryParams,
    NamingStandard,
    NamingStandardCreateRequest,
    NamingStandardQueryParams,
} from '@/types/dataStandard';
import type {PageResult, Result} from '@/types/common';

export function createNamingStandard(data: NamingStandardCreateRequest) {
    return request.post<Result<NamingStandard>>('/governance/data-standards/naming-standards', data);
}

export function updateNamingStandard(id: string, data: NamingStandardCreateRequest) {
    return request.put<Result<NamingStandard>>(`/governance/data-standards/naming-standards/${id}`, data);
}

export function deleteNamingStandard(id: string) {
    return request.delete<Result<null>>(`/governance/data-standards/naming-standards/${id}`);
}

export function getNamingStandard(id: string) {
    return request.get<Result<NamingStandard>>(`/governance/data-standards/naming-standards/${id}`);
}

export function queryNamingStandards(params: NamingStandardQueryParams) {
    return request.post<Result<PageResult<NamingStandard>>>('/governance/data-standards/naming-standards/page', params);
}

export function createFieldTypeStandard(data: FieldTypeStandardCreateRequest) {
    return request.post<Result<FieldTypeStandard>>('/governance/data-standards/field-type-standards', data);
}

export function updateFieldTypeStandard(id: string, data: FieldTypeStandardCreateRequest) {
    return request.put<Result<FieldTypeStandard>>(`/governance/data-standards/field-type-standards/${id}`, data);
}

export function deleteFieldTypeStandard(id: string) {
    return request.delete<Result<null>>(`/governance/data-standards/field-type-standards/${id}`);
}

export function getFieldTypeStandard(id: string) {
    return request.get<Result<FieldTypeStandard>>(`/governance/data-standards/field-type-standards/${id}`);
}

export function queryFieldTypeStandards(params: FieldTypeStandardQueryParams) {
    return request.post<Result<PageResult<FieldTypeStandard>>>('/governance/data-standards/field-type-standards/page', params);
}

export function runComplianceCheck(params: ComplianceCheckParams) {
    return request.post<Result<ComplianceCheckResult[]>>('/governance/data-standards/compliance-check', params);
}

export function queryComplianceCheckResults(params: ComplianceCheckParams) {
    return request.post<Result<ComplianceCheckResult[]>>('/governance/data-standards/compliance-check/results', params);
}

export function pageComplianceCheckResults(params: ComplianceCheckPageParams) {
    return request.post<Result<PageResult<ComplianceCheckResult>>>('/governance/data-standards/compliance-check/page', params);
}

export function ignoreComplianceCheckResult(resultId: string) {
    return request.post<Result<null>>(`/governance/data-standards/compliance-check/ignore/${resultId}`);
}

export function unignoreComplianceCheckResult(resultId: string) {
    return request.post<Result<null>>(`/governance/data-standards/compliance-check/unignore/${resultId}`);
}

export function getComplianceCheckSummary(params: ComplianceCheckParams) {
    return request.post<Result<ComplianceCheckSummary>>('/governance/data-standards/compliance-check/summary', params);
}

export function exportComplianceCheck(params: ComplianceCheckParams) {
    return request.post<Blob>('/governance/data-standards/compliance-check/export', params, {responseType: 'blob'});
}
