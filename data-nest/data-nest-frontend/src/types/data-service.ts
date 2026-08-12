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
