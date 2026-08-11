// Sprint 8 F3：质量报告 API（governance QualityReportController，/governance/quality/report/**）
// 查看四角色可用；导出仅治理员/超管（后端 SaCheckRole 兜底 1005）。
import request from '@/api/request';
import type {PageResult, Result} from '@/types/common';
import type {
    DatasourceScoreComparison,
    QualityIssueItem,
    QualityLevelTrendPoint,
    QualityReportOptions,
    QualityReportRequest,
    QualityReportSummary,
    QualityScoreDistribution,
    QualityScoreTrendPoint,
} from '@/types/quality-report';

const BASE = '/governance/quality/report';

/** 筛选联动选项（数据源含内置 Doris；库随数据源联动；质量任务） */
export const getQualityReportOptions = (datasourceId?: string) =>
    request.post<Result<QualityReportOptions>>(`${BASE}/options`, null, {
        params: {datasourceId: datasourceId || undefined},
    }).then(r => r.data);

/** KPI 汇总 */
export const getQualityReportSummary = (data: QualityReportRequest) =>
    request.post<Result<QualityReportSummary>>(`${BASE}/summary`, data).then(r => r.data);

/** 四档分布趋势（按天） */
export const getQualityLevelTrend = (data: QualityReportRequest) =>
    request.post<Result<QualityLevelTrendPoint[]>>(`${BASE}/level-trend`, data).then(r => r.data);

/** 评分趋势（tableId 空 = 按天聚合平均评分；非空 = 单表历史。单表缺表/表已删后端 4221——按空态处理，跳过全局错误提示） */
export const getQualityScoreTrend = (data: QualityReportRequest) =>
    request.post<Result<QualityScoreTrendPoint[]>>(`${BASE}/score-trend`, data, {skipErrorMessage: true}).then(r => r.data);

/** 表评分分布（环图；与时间无关） */
export const getQualityScoreDistribution = (data: QualityReportRequest) =>
    request.post<Result<QualityScoreDistribution>>(`${BASE}/score-distribution`, data).then(r => r.data);

/** 数据源质量对比（均分降序） */
export const getDatasourceComparison = (data: QualityReportRequest) =>
    request.post<Result<DatasourceScoreComparison[]>>(`${BASE}/datasource-comparison`, data).then(r => r.data);

/** 问题清单分页（WARNING/SEVERE 倒序） */
export const getQualityIssues = (data: QualityReportRequest) =>
    request.post<Result<PageResult<QualityIssueItem>>>(`${BASE}/issues`, data).then(r => r.data);

/** 导出 CSV（流式响应；用 downloadCsvBlob 做错误检出后触发下载） */
export const exportQualityReport = (data: QualityReportRequest) =>
    request.post<Blob>(`${BASE}/export`, data, {responseType: 'blob'});
