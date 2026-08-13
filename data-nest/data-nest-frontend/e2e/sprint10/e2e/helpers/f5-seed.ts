import {Api} from '../../../sprint6/helpers/api';
import {ADMIN, F2_USERS, psqlGov, psqlSys} from './f2-seed';

/**
 * Sprint 10 F5 E2E 测试数据辅助：数据分级分类（改级/批量/开白/审计/列表 + 三端闸门联动）。
 *
 * 数据来源：governance `/metadata/sensitivity/**`（分级端点）+ `/metadata/tables/**`（元数据表）。
 * - 复用现有 ONLINE 元数据表做改级/开白（target_products 有真实数据，可验 SQL 拦截），测后复位 PUBLIC + 清开白 + 清审计。
 * - 测试用户复用 F2 的 e2e_s10_engineer/analyst/govadmin（治理员/分析师/工程师），超管用 admin。
 * - 元数据表 ID / 数据源 ID 为 19 位 Long，全程字符串持有避免 Number 精度丢失。
 */

export {ADMIN, F2_USERS};

/** F5 测试表（复用现有 ONLINE 元数据表） */
export const F5_TABLES = {
    /** 主表：target_products（内置 Doris datanest，有真实数据，可验 SQL 拦截 + 资产详情 + API 闸门） */
    main: {id: '2083905048061136898', name: 'target_products', database: 'datanest', datasourceId: '-1'},
    /** 副表：e2e_s5_lin_target（历史 E2E 残留表，用于批量改级 + 开白，不干扰 target_products 状态） */
    aux: {id: '2084211478082658305', name: 'e2e_s5_lin_target', database: 'datanest', datasourceId: '-1'},
} as const;

const ALL_TABLE_IDS = Object.values(F5_TABLES).map((t) => t.id).join(',');

/** 分级级别枚举 */
export const LEVEL = {PUBLIC: 'PUBLIC', INTERNAL: 'INTERNAL', CONFIDENTIAL: 'CONFIDENTIAL'} as const;

/** 分级表列表项（pageSensitivityTables 返回） */
export interface SensitivityTableItem {
    tableId: string;
    tableName: string;
    databaseName: string;
    schemaName?: string;
    datasourceId: string;
    datasourceName?: string;
    sensitivityLevel: string;
    apiExempted: number;
    sourceStatus: string;
    taskSourceType?: string;
    ownerUserId?: string;
    ownerName?: string;
    createdBy?: string;
    createdByName?: string;
    createdAt?: string;
    updatedBy?: string;
    updatedByName?: string;
    updatedAt?: string;
}

/** 审计项（pageSensitivityAudit 返回） */
export interface SensitivityAuditItem {
    id: string;
    tableId: string;
    tableName: string;
    oldLevel: string;
    newLevel: string;
    action: string;
    remark?: string;
    operatorId?: string;
    operatorName?: string;
    createdAt: string;
}

// ==================== DB 直连辅助 ====================

/** 读取表当前敏感度 */
export function getSensitivity(tableId: string): string {
    return psqlGov(`SELECT sensitivity_level FROM metadata_table WHERE id = ${tableId}`).trim();
}

/** 读取表当前开白标记 */
export function getApiExempt(tableId: string): number {
    const v = psqlGov(`SELECT api_exempted FROM metadata_table WHERE id = ${tableId}`).trim();
    return v === '1' ? 1 : 0;
}

// ==================== 播种 / 清理 ====================

/** 确保临时测试用户存在（幂等），返回 userId */
async function ensureUser(api: Api, u: { username: string; password: string; roles: string[] }): Promise<void> {
    const existing = psqlSys(`SELECT id FROM sys_user WHERE username = '${u.username}'`);
    if (existing) return;
    await api.post('/system/users', {
        username: u.username,
        password: u.password,
        roles: u.roles,
        email: `${u.username}@test.io`,
    });
}

/** 播种：复位测试表 + 清审计 + 确保测试用户存在 */
export async function seedF5(): Promise<void> {
    resetF5Sensitivity();
    const api = await Api.create();
    try {
        await api.login(ADMIN.username, ADMIN.password);
        for (const u of Object.values(F2_USERS)) {
            await ensureUser(api, u);
        }
    } finally {
        await api.dispose();
    }
}

/** 复位 F5 测试表（PUBLIC + 清开白 + 清审计日志） */
export function resetF5Sensitivity(): void {
    psqlGov(`UPDATE metadata_table SET sensitivity_level='PUBLIC', api_exempted=0 WHERE id IN (${ALL_TABLE_IDS});`);
    psqlGov(`DELETE FROM sensitivity_change_log WHERE table_id IN (${ALL_TABLE_IDS});`);
}

/** 全量清理：复位敏感度 + 删除临时测试用户 */
export async function cleanupF5(): Promise<void> {
    try {
        resetF5Sensitivity();
        for (const u of Object.values(F2_USERS)) {
            psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = '${u.username}')`);
            psqlSys(`DELETE FROM sys_user WHERE username = '${u.username}'`);
        }
    } catch (e) {
        console.warn('[F5 cleanup] 清理异常（可忽略，人工复查）:', e);
    }
}

// ==================== API 辅助（诊断后端规则，request 方式） ====================

/** 以指定用户登录的 Api */
export async function loginAs(username: string, password: string): Promise<Api> {
    const api = await Api.create();
    await api.login(username, password);
    return api;
}

/** 单表改级（返回业务信封，供断言 code） */
export async function updateSensitivity(api: Api, tableId: string, newLevel: string) {
    return api.raw('PUT', `/governance/metadata/tables/${tableId}/sensitivity`, {newLevel});
}

/** 批量改级 */
export async function batchUpdateSensitivity(api: Api, tableIds: string[], newLevel: string) {
    return api.raw('POST', '/governance/metadata/tables/sensitivity/batch', {tableIds, newLevel});
}

/** 内部表 API 开白/取消开白 */
export async function updateApiExempt(api: Api, tableId: string, apiExempted: number) {
    return api.raw('PUT', `/governance/metadata/tables/${tableId}/api-exempt`, {apiExempted});
}

/** 分级审计分页 */
export async function pageAudit(api: Api, page = 1, pageSize = 20) {
    return api.get(`/governance/metadata/sensitivity/audit?page=${page}&pageSize=${pageSize}`);
}

/** 分级表列表分页 */
export async function pageSensitivityTables(api: Api, params: {
    page?: number;
    pageSize?: number;
    sensitivityLevel?: string;
    keyword?: string;
    datasourceId?: string;
} = {}) {
    const q = new URLSearchParams();
    q.set('page', String(params.page ?? 1));
    q.set('pageSize', String(params.pageSize ?? 20));
    if (params.sensitivityLevel) q.set('sensitivityLevel', params.sensitivityLevel);
    if (params.keyword) q.set('keyword', params.keyword);
    if (params.datasourceId) q.set('datasourceId', params.datasourceId);
    return api.get(`/governance/metadata/sensitivity/tables?${q.toString()}`);
}
