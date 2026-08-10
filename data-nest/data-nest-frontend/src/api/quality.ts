import request from './request';
import type {
    QualityCheckBatch,
    QualityCheckQueryParams,
    QualityRule,
    QualityRuleBatchCreateRequest,
    QualityRuleCreateRequest,
    QualityRuleQueryParams,
    QualityRuleTemplate,
    QualityRuleTemplateCreateRequest,
    QualityRuleTemplateQueryParams,
    QualityRuleUpdateRequest,
    QualityJob,
    QualityJobCreateRequest,
    QualityJobQueryParams,
    QualityJobUpdateRequest,
    QualityScore,
    QualityScoreConfig,
    QualityScoreQueryParams,
    QualityScriptTestResult,
    QualitySqlPreviewResult,
    QualityTableRuleResult,
} from '@/types/quality';
import type {PageResult, Result} from '@/types/common';

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

/** 开启质量任务调度（scheduled_enabled=1，cron 为空时后端抛错） */
export function startQualityJobSchedule(id: string) {
    return request.post<Result<null>>(`/governance/quality/jobs/${id}/schedule/start`);
}

/** 关闭质量任务调度（scheduled_enabled=0） */
export function stopQualityJobSchedule(id: string) {
    return request.post<Result<null>>(`/governance/quality/jobs/${id}/schedule/stop`);
}

/** 立即执行质量任务（异步跑其引用的全部启用规则，生成一个批次） */
export function executeQualityJob(id: string) {
    return request.post<Result<null>>(`/governance/quality/jobs/${id}/execute`);
}

// ============ 质量规则 ============

/** 分页查询规则（Sprint 7 规则独立菜单，支持关键字/类型/状态/所属任务筛选） */
export function queryQualityRules(params: QualityRuleQueryParams) {
    return request.post<Result<PageResult<QualityRule>>>('/governance/quality/rules/page', params);
}

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

/** 立即执行质量规则（单条试跑，生成独立批次，jobId 为空） */
export function executeQualityRule(id: string) {
    return request.post<Result<null>>(`/governance/quality/rules/${id}/execute`);
}

// ============ 质量检查执行历史（Sprint 8 执行层） ============

/** 批次分页查询（按任务/触发方式/状态过滤） */
export function queryQualityChecks(params: QualityCheckQueryParams) {
    return request.post<Result<PageResult<QualityCheckBatch>>>('/governance/quality/checks/page', params);
}

/** 批次详情（含规则明细） */
export function getQualityCheckDetail(id: string) {
    return request.get<Result<QualityCheckBatch>>(`/governance/quality/checks/${id}`);
}

/** 模板批量应用（1 模板 + 多表） */
export function batchCreateQualityRules(data: QualityRuleBatchCreateRequest) {
    return request.post<Result<number>>('/governance/quality/rules/batch', data);
}

// ============ 表级质量评分（Sprint 6 NG8） ============

/** 单表评分（元数据「质量」页签概览） */
export function getQualityScoreByTable(tableId: string) {
    return request.get<Result<QualityScore | null>>(`/governance/quality/scores/table/${tableId}`);
}

/** 评分列表分页（按关键字/数据源/健康度筛选） */
export function queryQualityScores(params: QualityScoreQueryParams) {
    return request.post<Result<PageResult<QualityScore>>>('/governance/quality/scores/page', params);
}

/** 按表查该表所有启用规则 + 最近一次检查结果（元数据「质量」页签规则结果列表） */
export function getTableQualityRuleResults(tableId: string) {
    return request.get<Result<QualityTableRuleResult[]>>(`/governance/quality/scores/table/${tableId}/rules`);
}

/** 按表执行全部启用规则（异步投递 worker） */
export function executeTableQualityRules(tableId: string) {
    return request.post<Result<null>>(`/governance/quality/scores/table/${tableId}/execute`);
}

/** Sprint 7 F4：PYTHON 规则脚本试跑（保存前验证，governance 本地沙箱） */
export const testQualityPythonScript = (data: { tableId: string; pythonScript: string }) =>
    request.post<Result<QualityScriptTestResult>>('/governance/quality/rules/test-script', data, {timeout: 320000}).then(r => r.data);

/** Sprint 7 F4：CUSTOM_SQL 执行预览（多指标列 + 样例行，供选 resultMetric） */
export const previewExecuteQualitySql = (data: {
    tableId: string;
    sqlExpression: string;
    columnName?: string;
    rangeMin?: number;
    rangeMax?: number;
}) => request.post<Result<QualitySqlPreviewResult>>('/governance/quality/rules/preview-execute', data, {timeout: 60000}).then(r => r.data);

/** 读质量评分全局扣分配置（「扣分配置」弹窗回显） */
export function getQualityScoreConfig() {
    return request.get<Result<QualityScoreConfig>>('/governance/quality/scores/config');
}

/** 更新质量评分全局扣分配置 */
export function updateQualityScoreConfig(data: QualityScoreConfig) {
    return request.put<Result<null>>('/governance/quality/scores/config', data);
}
