// Sprint 10 F1：SQL 查询终端（data-service 域）类型。
// 对齐后端 data-nest-data-service 的 SqlDatasourceDTO / SqlExecuteRequest / SqlExecuteResult / SqlQueryHistory。

/** SQL 终端数据源下拉项（内置 Doris = -1 + 状态 NORMAL 的平台数据源） */
export interface SqlDatasource {
    id: string;
    name: string;
    type: string;
    builtin: boolean;
    databaseName?: string;
}

/** SQL 终端执行请求（datasourceId 为 Long，统一 string 传输；内置 Doris 传 '-1'） */
export interface SqlExecuteRequest {
    datasourceId: string;
    sql: string;
    /** 查询超时秒数（默认取服务配置 60） */
    timeoutSeconds?: number;
    /** 前端生成的查询标识（UUID），用于「停止」按钮取消本次查询 */
    queryId?: string;
}

/** SQL 终端执行结果 */
export interface SqlExecuteResult {
    columns: string[];
    rows: Record<string, unknown>[];
    truncated: boolean;
    durationMs: number;
    rowCount: number;
    /** 本次 SQL 引用的表数量 */
    tableCount: number;
    /** 命中机密级敏感表的数量（成功返回恒为 0，表示未触碰机密数据） */
    confidentialHits: number;
}

/** SQL 终端取消请求 */
export interface SqlCancelRequest {
    queryId: string;
}

/** SQL 终端导出请求（format: 'XLSX' | 'CSV'） */
export interface SqlExportRequest {
    datasourceId: string;
    sql: string;
    format: 'XLSX' | 'CSV';
    /** 查询超时秒数（默认取服务配置 60） */
    timeoutSeconds?: number;
}

/** SQL 查询历史 */
export interface SqlQueryHistory {
    id: string;
    userId: string;
    datasourceId: string;
    sqlText: string;
    durationMs?: number;
    rowCount?: number;
    /** 错误信息（失败查询记录，用于历史列表展示失败标记 + 回填后可重试） */
    errorMessage?: string;
    createdAt?: string;
}

// ============ Sprint 10 F2：数据 API 管理 + API Key 管理 ============

/** API 生命周期状态：CREATED 未发布 / PUBLISHED 已发布 / DISABLED 已下线 */
export type DataApiStatus = 'CREATED' | 'PUBLISHED' | 'DISABLED';

/** 表敏感度（governance metadata_table.sensitivity_level；未打标默认 PUBLIC） */
export type SensitivityLevel = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL';

/** API Key 状态 */
export type ApiKeyStatus = 'ENABLED' | 'DISABLED';

export const DATA_API_STATUS_LABEL: Record<DataApiStatus, string> = {
    CREATED: '未发布',
    PUBLISHED: '已发布',
    DISABLED: '已下线',
};

export const SENSITIVITY_LABEL: Record<SensitivityLevel, string> = {
    PUBLIC: '公开',
    INTERNAL: '内部',
    CONFIDENTIAL: '机密',
};

export const API_KEY_STATUS_LABEL: Record<ApiKeyStatus, string> = {
    ENABLED: '启用',
    DISABLED: '禁用',
};

// ============ Sprint 13 F1：自定义查询 SQL（双形态：选表 / 自定义 SQL） ============

/** 查询定义形态：TABLE_SELECT 选表（一期流程）/ CUSTOM_SQL 自定义 SQL */
export type DataApiQueryType = 'TABLE_SELECT' | 'CUSTOM_SQL';

/** 自定义 SQL 参数类型（对齐 CancelableSqlExecutor 启发式推断，可手动修正） */
export type CustomSqlParamType = 'LONG' | 'DECIMAL' | 'DATE' | 'DATETIME' | 'STRING' | 'BOOLEAN';

export const CUSTOM_SQL_PARAM_TYPE_LABEL: Record<CustomSqlParamType, string> = {
    LONG: '整数 LONG',
    DECIMAL: '小数 DECIMAL',
    DATE: '日期 DATE',
    DATETIME: '日期时间 DATETIME',
    STRING: '字符串 STRING',
    BOOLEAN: '布尔 BOOLEAN',
};

/** 自定义 SQL 参数定义（CUSTOM_SQL 形态，与 SQL 内 :param 一一对应） */
export interface CustomSqlParamDef {
    /** 参数名（对应 SQL 内 :param 命名占位符） */
    name: string;
    /** 参数类型：LONG / DECIMAL / DATE / DATETIME / STRING / BOOLEAN */
    type: CustomSqlParamType;
    /** 是否必填（默认 true；选填参数缺省时不带该条件） */
    required: boolean;
    /** 默认值（选填参数缺省时使用；统一 string 传输，后端按 type 强转） */
    defaultValue?: string | null;
}

/** SQL 涉及表（创建/编辑时后端解析落库，供权限校验与血缘；前端展示用） */
export interface InvolvedTable {
    datasourceId?: string;
    database?: string;
    schema?: string;
    table: string;
}

/** API 分页列表项（Long 计数序列为 string） */
export interface DataApiPageItem {
    id: string;
    name: string;
    path: string;
    method: string;
    datasourceId: string;
    datasourceName?: string;
    databaseName: string;
    schemaName?: string;
    tableName: string;
    /** 查询定义形态（Sprint 13；缺省视为选表） */
    queryType?: DataApiQueryType;
    /** 源表敏感度；governance 不可达时降级为空（显示「未知」） */
    sensitivityLevel?: SensitivityLevel;
    status: DataApiStatus;
    boundKeyCount: string;
    calls7d: string;
    createdBy?: string;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}

/** API 列表页统计卡汇总 */
export interface DataApiSummary {
    publishedCount: string;
    createdCount: string;
    disabledCount: string;
    totalCalls7d: string;
}

/** 参数化筛选定义（EQ 等值 / RANGE 范围，查询条件 AND 组合） */
export interface ApiParamDef {
    field: string;
    type: 'EQ' | 'RANGE';
}

/** API 定义（params_json 解析形态）；fields 空 = 全部字段 */
export interface DataApiDefinition {
    filters?: ApiParamDef[];
    fields?: string[];
}

/** 自动文档参数说明 */
export interface DataApiDocParam {
    name: string;
    description: string;
}

/** API 自动文档 */
export interface DataApiDoc {
    method: string;
    path: string;
    /** 经网关完整调用路径（/api/data-service/open-api/v1/...） */
    fullPath: string;
    auth: string;
    params: DataApiDocParam[];
    response: string;
    curl: string;
}

/** 绑定 Key 简要信息（API 详情用） */
export interface ApiKeyBrief {
    id: string;
    name: string;
    status: ApiKeyStatus;
}

/** API 详情 */
export interface DataApiDetail {
    id: string;
    name: string;
    path: string;
    method: string;
    datasourceId: string;
    datasourceName?: string;
    databaseName: string;
    schemaName?: string;
    tableName: string;
    /** 查询定义形态（Sprint 13；缺省视为选表） */
    queryType?: DataApiQueryType;
    /** 自定义 SQL 文本（CUSTOM_SQL 形态） */
    sqlText?: string;
    /** 自定义 SQL 参数定义（CUSTOM_SQL 形态） */
    sqlParams?: CustomSqlParamDef[];
    /** SQL 涉及表（CUSTOM_SQL 形态，创建/编辑时后端解析落库） */
    involvedTables?: InvolvedTable[];
    sensitivityLevel?: SensitivityLevel;
    metadataTableId?: string;
    definition: DataApiDefinition;
    orderBy?: string;
    paginated?: number;
    pageSizeMax?: number;
    status: DataApiStatus;
    doc: DataApiDoc;
    boundKeys: ApiKeyBrief[];
    calls7d: string;
    createdBy?: string;
    createdByName?: string;
    updatedByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** 创建 API 请求（datasourceId Long 以 string 传输；内置 Doris 传 '-1'） */
export interface DataApiCreateRequest {
    name: string;
    /** 可传自定义段 orders 或完整 /open-api/v1/orders，后端统一归一 */
    path: string;
    datasourceId: string;
    /** 查询定义形态：TABLE_SELECT 选表（默认，一期流程）/ CUSTOM_SQL 自定义 SQL */
    queryType?: DataApiQueryType;
    /** 自定义 SQL 文本（CUSTOM_SQL 形态必填，只读 SELECT，:param 命名参数） */
    sqlText?: string;
    /** 自定义 SQL 参数定义（CUSTOM_SQL 形态，与 SQL :param 一一对应） */
    sqlParams?: CustomSqlParamDef[];
    /** 库名（CUSTOM_SQL 形态由 SQL 决定，可不传） */
    databaseName?: string;
    schemaName?: string;
    /** 表名（CUSTOM_SQL 形态由 SQL 决定，可不传） */
    tableName?: string;
    metadataTableId?: string;
    filters?: ApiParamDef[];
    /** 返回字段白名单（空 = 全部字段） */
    fields?: string[];
    orderBy?: string;
    paginated?: number;
    pageSizeMax?: number;
}

/** 编辑 API 请求（数据源/库/表绑定不可改；CUSTOM_SQL 形态可改 SQL/参数） */
export interface DataApiUpdateRequest {
    name: string;
    path: string;
    /** 查询定义形态：TABLE_SELECT 选表（默认）/ CUSTOM_SQL 自定义 SQL */
    queryType?: DataApiQueryType;
    /** 自定义 SQL 文本（CUSTOM_SQL 形态必填，只读 SELECT，:param 命名参数） */
    sqlText?: string;
    /** 自定义 SQL 参数定义（CUSTOM_SQL 形态，与 SQL :param 一一对应） */
    sqlParams?: CustomSqlParamDef[];
    filters?: ApiParamDef[];
    fields?: string[];
    orderBy?: string;
    paginated?: number;
    pageSizeMax?: number;
}

/** API Key 分页列表项 */
export interface ApiKeyPageItem {
    id: string;
    name: string;
    status: ApiKeyStatus;
    qpsLimit: number;
    boundApiCount: string;
    /** 近 7 天调用（'0' = 僵尸 Key，建议停用） */
    calls7d: string;
    createdBy?: string;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}

/** API Key 详情（编辑预填；明文 Key 只在创建时返回） */
export interface ApiKeyDetail {
    id: string;
    name: string;
    status: ApiKeyStatus;
    qpsLimit: number;
    apiIds: string[];
    pipelineIds: string[];
    createdByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** 创建/编辑 Key 请求（apiIds 全量重绑） */
export interface ApiKeySaveRequest {
    name: string;
    qpsLimit: number;
    apiIds?: string[];
    pipelineIds?: string[];
}

/** 创建 Key 响应（apiKey 明文仅本次返回） */
export interface ApiKeyCreateResult {
    id: string;
    name: string;
    apiKey: string;
    qpsLimit: number;
    status: ApiKeyStatus;
    createdAt?: string;
}

// ============ Sprint 10 F3：API 网关调用统计 ============

/** 统计时间范围（对齐后端 range=24h|7d|30d） */
export type StatsRange = '24h' | '7d' | '30d';

/** 时间桶趋势点（bucket ISO 时间；total/failed 为 Long → string） */
export interface StatsTrendPoint {
    bucket: string;
    total: string;
    failed?: string;
}

/** 全局 KPI 聚合（Long 计数 → string；Double 比率 → number） */
export interface StatsOverview {
    totalCalls: string;
    successRate: number;
    p95Ms: number;
    rateLimitedCount: string;
    rateLimitRatio: number;
}

/** 健康级别（对齐告警 PASS/WARNING/SEVERE） */
export type HealthLevel = 'PASS' | 'WARNING' | 'SEVERE';

/** 单 API 健康分级明细 */
export interface StatsHealthItem {
    apiId: string;
    name: string;
    path?: string | null;
    level: HealthLevel;
    totalCalls: string;
    errorRate: number;
    p95Ms: number;
    rateLimitRatio: number;
}

/** API 健康分布 */
export interface StatsHealthDistribution {
    overallScore: number;
    healthyCount: number;
    warningCount: number;
    severeCount: number;
    items: StatsHealthItem[];
}

/** Top API 排行项（deleted = 软删/已清除，前端灰显 + 不提供详情跳转） */
export interface StatsTopApi {
    apiId: string;
    name: string;
    path?: string | null;
    deleted?: boolean | null;
    calls: string;
}

/** 调用方 Key 排行项（zombie = 近 7 天 0 调用） */
export interface StatsTopKey {
    keyId: string;
    name: string;
    calls: string;
    zombie: boolean;
    /** 绑定的 API 数（0 = 未绑定） */
    boundApiCount?: number;
}

/** 错误码分布项（ratio 为占错误总量比例 0~1；429 条目带 top429ApiName） */
export interface StatsErrorCode {
    statusCode: number;
    count: string;
    ratio: number;
    /** 429 限流命中最多的 API 名（null = 无 429 或 API 已删除） */
    top429ApiName?: string | null;
}

/** 状态码三档汇总（2xx 成功 / 4xx 客户端 / 5xx 服务端） */
export interface StatusBreakdown {
    success: string;
    clientError: string;
    serverError: string;
}

/** 调用明细行（单 API 最近调用） */
export interface ApiCallLogItem {
    keyName?: string | null;
    statusCode: number;
    durationMs?: number | null;
    createdAt: string;
}

/** 单 API 调用统计 */
export interface ApiStats {
    totalCalls: string;
    successRate: number;
    avgMs: number;
    p95Ms: number;
    todayCalls: string;
    trend: StatsTrendPoint[];
    recentLogs: ApiCallLogItem[];
    hourly: StatsTrendPoint[];
    topKeys: StatsTopKey[];
    statusBreakdown: StatusBreakdown;
}

/** 管道订阅监控统计（F4 连接监控） */
export interface SubscriptionStats {
    onlineConnections: number;
    todayEvents: string;
    p95Ms: string;
    failedSends: string;
    subscribers: SubscriberItem[];
}

/** 订阅方 Key 项（F4 连接监控） */
export interface SubscriberItem {
    keyId: string;
    keyName: string;
    online: boolean;
    receivedEvents: string;
    lastEventAt?: string | null;
    createdByName?: string;
    createdAt?: string;
    updatedByName?: string;
    updatedAt?: string;
}
