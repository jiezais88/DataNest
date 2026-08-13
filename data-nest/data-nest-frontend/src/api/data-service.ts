// Sprint 10 F1：SQL 查询终端 API（data-service 域，经网关 /api/data-service/** 路由）。
import request from './request';
import type {Result, PageResult} from '@/types/common';
import type {
    ApiCallLogItem,
    ApiKeyCreateResult,
    ApiKeyDetail,
    ApiKeyPageItem,
    ApiKeySaveRequest,
    ApiStats,
    DataApiCreateRequest,
    DataApiDetail,
    DataApiPageItem,
    DataApiStatus,
    DataApiSummary,
    DataApiUpdateRequest,
    SqlCancelRequest,
    SqlDatasource,
    SqlExecuteRequest,
    SqlExecuteResult,
    SqlExportRequest,
    SqlQueryHistory,
    StatsErrorCode,
    StatsHealthDistribution,
    StatsOverview,
    StatsRange,
    StatsTopApi,
    StatsTopKey,
    StatsTrendPoint,
    StatusBreakdown,
    SubscriberItem,
    SubscriptionStats,
} from '@/types/data-service';

/**
 * 执行只读 SQL。默认超时 60s，axios 请求超时放大到 70s（服务端超时后 HTTP 才返回 9003）。
 * skipErrorMessage：SQL 业务错误（9001/9002/9003/9004/9012）由页面行内展示，不走全局弹窗。
 * signal：与「停止」按钮联动（AbortController）。
 */
export function executeSql(data: SqlExecuteRequest, signal?: AbortSignal) {
    return request.post<Result<SqlExecuteResult>>('/data-service/sql-console/execute', data, {
        timeout: 70000,
        signal,
        skipErrorMessage: true,
    });
}

/** 停止查询（幂等；服务端中断线程 + 关闭连接） */
export function cancelQuery(data: SqlCancelRequest) {
    return request.post<Result<boolean>>('/data-service/sql-console/cancel', data);
}

/** SQL 终端数据源下拉（内置 Doris + 状态 NORMAL 的平台数据源） */
export function listSqlDatasources() {
    return request.get<Result<SqlDatasource[]>>('/data-service/sql-console/datasources');
}

/** 我的查询历史（分页） */
export function getQueryHistory(page: number, pageSize: number) {
    return request.get<Result<PageResult<SqlQueryHistory>>>(
        `/data-service/sql-console/history?page=${page}&pageSize=${pageSize}`,
    );
}

/** 清空我的查询历史 */
export function clearQueryHistory() {
    return request.delete<Result<null>>('/data-service/sql-console/history');
}

/**
 * 导出查询结果（后端生成 xlsx/csv，流式响应）。
 * format: 'XLSX' | 'CSV'；用 downloadExportBlob 做错误检出后触发下载。
 */
export function exportSqlResult(data: SqlExportRequest) {
    return request.post<Blob>('/data-service/sql-console/export', data, {responseType: 'blob'});
}

// ============ Sprint 10 F2：数据 API 管理 ============

/** API 列表（分页；scope=mine 仅看我创建的；keyword 匹配名称/路径；status 精确过滤） */
export function pageDataApis(params: {
    page: number;
    pageSize: number;
    scope?: 'mine';
    keyword?: string;
    status?: DataApiStatus | '';
}) {
    const search = new URLSearchParams();
    search.set('page', String(params.page));
    search.set('pageSize', String(params.pageSize));
    if (params.scope) search.set('scope', params.scope);
    if (params.keyword) search.set('keyword', params.keyword);
    if (params.status) search.set('status', params.status);
    return request.get<Result<PageResult<DataApiPageItem>>>(`/data-service/apis/page?${search.toString()}`);
}

/** API 汇总（列表页统计卡：已发布/待发布/已下线/近 7 天总调用） */
export function getDataApiSummary() {
    return request.get<Result<DataApiSummary>>('/data-service/apis/summary');
}

/** API 详情（定义 + 自动文档 + 绑定 Key + 近 7 天调用） */
export function getDataApi(id: string) {
    return request.get<Result<DataApiDetail>>(`/data-service/apis/${id}`);
}

/** 创建 API（后端校验敏感度闸门 + 路径归一查重） */
export function createDataApi(data: DataApiCreateRequest) {
    return request.post<Result<DataApiDetail>>('/data-service/apis', data);
}

/** 编辑 API（名称/路径/参数/字段/排序/分页；数据源/库/表绑定不可改） */
export function updateDataApi(id: string, data: DataApiUpdateRequest) {
    return request.put<Result<DataApiDetail>>(`/data-service/apis/${id}`, data);
}

/** 发布（CREATED/DISABLED → PUBLISHED，幂等） */
export function publishDataApi(id: string) {
    return request.post<Result<null>>(`/data-service/apis/${id}/publish`);
}

/** 下线（PUBLISHED → DISABLED，幂等） */
export function disableDataApi(id: string) {
    return request.post<Result<null>>(`/data-service/apis/${id}/disable`);
}

/** 删除（软删，保留调用统计，清理 Key 绑定） */
export function deleteDataApi(id: string) {
    return request.delete<Result<null>>(`/data-service/apis/${id}`);
}

// ============ Sprint 10 F2：API Key 管理 ============

/** Key 列表（分页；含绑定 API 数 + 近 7 天调用，0 = 僵尸 Key） */
export function pageApiKeys(params: { page: number; pageSize: number; keyword?: string; status?: string }) {
    const search = new URLSearchParams();
    search.set('page', String(params.page));
    search.set('pageSize', String(params.pageSize));
    if (params.keyword) search.set('keyword', params.keyword);
    if (params.status) search.set('status', params.status);
    return request.get<Result<PageResult<ApiKeyPageItem>>>(`/data-service/api-keys/page?${search.toString()}`);
}

/** Key 详情（编辑弹窗预填当前绑定 API） */
export function getApiKey(id: string) {
    return request.get<Result<ApiKeyDetail>>(`/data-service/api-keys/${id}`);
}

/** 创建 Key（明文仅本次响应返回，后端只存哈希） */
export function createApiKey(data: ApiKeySaveRequest) {
    return request.post<Result<ApiKeyCreateResult>>('/data-service/api-keys', data);
}

/** 编辑 Key（改名 / 限流 QPS / 全量重绑 API） */
export function updateApiKey(id: string, data: ApiKeySaveRequest) {
    return request.put<Result<null>>(`/data-service/api-keys/${id}`, data);
}

/** 快捷启用（幂等） */
export function enableApiKey(id: string) {
    return request.post<Result<null>>(`/data-service/api-keys/${id}/enable`);
}

/** 快捷禁用（幂等；禁用后对外调用立即 401） */
export function disableApiKey(id: string) {
    return request.post<Result<null>>(`/data-service/api-keys/${id}/disable`);
}

/** 删除 Key（同时清理 API 绑定与管道订阅授权） */
export function deleteApiKey(id: string) {
    return request.delete<Result<null>>(`/data-service/api-keys/${id}`);
}

// ============ Sprint 10 F3：API 运行统计（全局）+ 单 API 统计 ============

/** 全局 KPI 聚合 */
export function getStatsOverview(range: StatsRange) {
    return request.get<Result<StatsOverview>>(`/data-service/stats/overview?range=${range}`);
}

/** 全局调用量趋势（双线：调用量 + 失败数） */
export function getStatsTrend(range: StatsRange) {
    return request.get<Result<StatsTrendPoint[]>>(`/data-service/stats/trend?range=${range}`);
}

/** API 健康分布（综合健康分 + 健康/警告/严重） */
export function getStatsHealthDistribution(range: StatsRange) {
    return request.get<Result<StatsHealthDistribution>>(`/data-service/stats/health-distribution?range=${range}`);
}

/** Top API 调用排行 */
export function getStatsTopApis(range: StatsRange, limit = 5) {
    return request.get<Result<StatsTopApi[]>>(`/data-service/stats/top-apis?range=${range}&limit=${limit}`);
}

/** 错误码分布（4xx/5xx TopN） */
export function getStatsErrorCodes(range: StatsRange, limit = 5) {
    return request.get<Result<StatsErrorCode[]>>(`/data-service/stats/error-codes?range=${range}&limit=${limit}`);
}

/** 调用方 Key 排行（含僵尸 Key） */
export function getStatsTopKeys(range: StatsRange, limit = 5) {
    return request.get<Result<StatsTopKey[]>>(`/data-service/stats/top-keys?range=${range}&limit=${limit}`);
}

/** 限流命中趋势（429 按时间桶） */
export function getStatsRateLimitTrend(range: StatsRange) {
    return request.get<Result<StatsTrendPoint[]>>(`/data-service/stats/rate-limit-trend?range=${range}`);
}

/** 单 API 调用统计（KPI + 调用量趋势 + 今日小时分布 + Key 排行 + 错误码分布 + 最近明细） */
export function getApiStats(id: string, range: StatsRange) {
    return request.get<Result<ApiStats>>(`/data-service/apis/${id}/stats?range=${range}`);
}

// ============ Sprint 10 F4：实时订阅监控（连接监控） ============

/** 管道订阅监控（在线连接/今日事件/延迟 P95/推送失败 + 订阅方 Key 列表） */
export function getSubscriptionStats(pipelineId: string) {
    return request.get<Result<SubscriptionStats>>(`/data-service/subscriptions/${pipelineId}/stats`);
}
