import {execSync} from 'child_process';
import {request as pwRequest} from '@playwright/test';
import {Api} from '../../../sprint6/helpers/api';
import {ADMIN, TARGET, getTargetTableId, psqlDs} from './f2-seed';

/**
 * Sprint 10 F3 E2E 测试数据辅助：自播种自清理。
 *
 * 范围：F3 API 网关（对外调用入口 / 限流 / 熔断 / 调用统计）。
 * - 对外调用不登录、带 X-API-Key 头直调 /open-api/v1/{path}（经网关），与业务系统视角一致。
 * - 主测试表复用 F2 的 datanest.target_products（10 行：手机 6 / 电脑 2 / 耳机 2）。
 * - 测试 API/Key 统一 e2e_s10_f3_ 前缀命名，cleanup 物理删除。
 */

export {ADMIN, TARGET, getTargetTableId, psqlDs};

export const F3_PREFIX = 'e2e_s10_f3_';
export const F3_PATH_PREFIX = 'e2e-s10-f3-';

/** 对外 open-api 调用结果（HTTP 语义：状态码 + Retry-After 头 + 业务信封 body） */
export interface OpenApiResponse {
    status: number;
    retryAfter: string | null;
    body: {code?: number; message?: string; data?: any} | null;
}

/** 对外调用：带 X-API-Key 头直调 open-api（不登录），返回 {status, retryAfter, body} */
export async function openApiCall(
    path: string,
    apiKey?: string,
    query?: Record<string, string>,
): Promise<OpenApiResponse> {
    const ctx = await pwRequest.newContext();
    const url = new URL(`http://localhost:8080/api/data-service/open-api/v1/${path}`);
    if (query) {
        for (const [k, v] of Object.entries(query)) url.searchParams.set(k, v);
    }
    const headers: Record<string, string> = {};
    if (apiKey !== undefined && apiKey !== null) headers['X-API-Key'] = apiKey;
    const res = await ctx.fetch(url.toString(), {headers});
    const text = await res.text();
    let body: any = null;
    try {
        body = text ? JSON.parse(text) : null;
    } catch {
        // 非 JSON 响应（异常情况）
    }
    const retryAfter = res.headers()['retry-after'] ?? null;
    await ctx.dispose();
    return {status: res.status(), retryAfter, body};
}

/** 以 admin 创建数据 API，返回 detail（含 id） */
export async function createApi(body: Record<string, unknown>): Promise<any> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post('/data-service/apis', body);
    await api.dispose();
    return detail;
}

/** 以 admin 创建 API Key，返回 {id, name, apiKey明文, ...} */
export async function createKey(body: Record<string, unknown>): Promise<any> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const result = await api.post('/data-service/api-keys', body);
    await api.dispose();
    return result;
}

/** 以 admin 执行管理端写操作（publish/disable 等 POST，或 DELETE） */
export async function adminAction(method: 'POST' | 'DELETE', path: string, body?: unknown): Promise<any> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const result = method === 'POST' ? await api.post(path, body) : await api.del(path);
    await api.dispose();
    return result;
}

/** 物理删除 F3 前缀的 API/Key/绑定（比软删干净，路径可复用） */
export function cleanupF3(): void {
    psqlDs(`
        DELETE FROM api_key_binding
        WHERE key_id IN (SELECT id FROM api_key WHERE name LIKE '${F3_PREFIX}%')
           OR api_id IN (SELECT id FROM data_api WHERE name LIKE '${F3_PREFIX}%');
        DELETE FROM api_key WHERE name LIKE '${F3_PREFIX}%';
        DELETE FROM data_api WHERE name LIKE '${F3_PREFIX}%';
    `);
}

/** 清理 F3 前缀相关的 api_call_log（统计测试保证单 API 口径确定） */
export function cleanupF3CallLog(): void {
    psqlDs(`
        DELETE FROM api_call_log
        WHERE api_id IN (SELECT id FROM data_api WHERE name LIKE '${F3_PREFIX}%')
           OR key_id IN (SELECT id FROM api_key WHERE name LIKE '${F3_PREFIX}%');
    `);
}
