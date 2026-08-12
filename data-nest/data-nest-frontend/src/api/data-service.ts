// Sprint 10 F1：SQL 查询终端 API（data-service 域，经网关 /api/data-service/** 路由）。
import request from './request';
import type {Result, PageResult} from '@/types/common';
import type {
    SqlCancelRequest,
    SqlDatasource,
    SqlExecuteRequest,
    SqlExecuteResult,
    SqlQueryHistory,
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
