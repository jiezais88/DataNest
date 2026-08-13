import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 10 F2 E2E 测试数据辅助：自播种自清理（用户确认 2026-08-12）。
 *
 * 范围：数据 API 管理端 + API Key 管理（F3 对外调用入口未实现，不在本次覆盖）。
 *
 * 数据策略：
 * - 临时用户 3 个（e2e_s10_engineer / e2e_s10_analyst / e2e_s10_govadmin），经
 *   POST /system/users 创建（幂等），cleanup 直接 SQL 删除，不依赖其它 sprint 种子。
 * - 主测试表：内置 Doris `datanest.target_products`（8 字段元数据齐全），
 *   敏感度闸门测试直接 UPDATE metadata_table 改级，测完恢复 PUBLIC + api_exempted=0。
 * - 测试 API/Key 统一 e2e_s10_ 前缀命名，cleanup 物理删除（比软删干净，路径可复用）。
 */
export const ADMIN = {username: 'admin', password: 'admin123'};

export const F2_USERS = {
    engineer: {username: 'e2e_s10_engineer', password: 'Test123456', roles: ['DATA_ENGINEER']},
    analyst: {username: 'e2e_s10_analyst', password: 'Test123456', roles: ['DATA_ANALYST']},
    govAdmin: {username: 'e2e_s10_govadmin', password: 'Test123456', roles: ['GOVERNANCE_ADMIN']},
} as const;

/** F2 主测试表：内置 Doris（datasourceId=-1）datanest 库 target_products */
export const TARGET = {
    datasourceId: '-1',
    databaseName: 'datanest',
    tableName: 'target_products',
    /** 8 个字段（metadata_column 实测）：id/name/price/stock/category/status/created_at/updated_at */
    columns: ['id', 'name', 'price', 'stock', 'category', 'status', 'created_at', 'updated_at'],
} as const;

/** 测试数据命名前缀（API 名称 / Key 名称 / 路径段） */
export const PREFIX = 'e2e_s10_';
/** API 路径段前缀（路径段规则：^[a-z0-9][a-z0-9-_]{0,99}$） */
export const PATH_PREFIX = 'e2e-s10-';

// ==================== DB 直连辅助（docker exec -i 传 stdin，Windows 引号安全） ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

/** 数据服务库（data_api / api_key / api_key_binding） */
export const psqlDs = (sql: string): string => psql('datanest_dataservice', sql);
/** 治理库（metadata_table 敏感度） */
export const psqlGov = (sql: string): string => psql('datanest_governance', sql);
/** 系统库（用户） */
export const psqlSys = (sql: string): string => psql('datanest_system', sql);

// ==================== 播种（beforeAll） ====================

/** 确保 target_products 为 PUBLIC + api_exempted=0（复位到默认，幂等） */
export function resetSensitivity(): void {
    psqlGov(
        `UPDATE metadata_table SET sensitivity_level='PUBLIC', api_exempted=0
         WHERE datasource_id=-1 AND database_name='${TARGET.databaseName}' AND table_name='${TARGET.tableName}'`,
    );
}

/** 直接改级（敏感度闸门造数）：level=PUBLIC|INTERNAL|CONFIDENTIAL，exempt=0|1 */
export function setSensitivity(level: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL', exempt: 0 | 1 = 0): void {
    psqlGov(
        `UPDATE metadata_table SET sensitivity_level='${level}', api_exempted=${exempt}
         WHERE datasource_id=-1 AND database_name='${TARGET.databaseName}' AND table_name='${TARGET.tableName}'`,
    );
}

/** 读取当前敏感度（返回 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL'） */
export function getSensitivity(): string {
    const r = psqlGov(
        `SELECT sensitivity_level FROM metadata_table
         WHERE datasource_id=-1 AND database_name='${TARGET.databaseName}' AND table_name='${TARGET.tableName}'`,
    );
    return r || 'PUBLIC';
}

/** 读取 target_products 的 metadata_table id（创建 API 的 metadataTableId） */
export function getTargetTableId(): string {
    const r = psqlGov(
        `SELECT id FROM metadata_table
         WHERE datasource_id=-1 AND database_name='${TARGET.databaseName}' AND table_name='${TARGET.tableName}'`,
    );
    if (!r) throw new Error(`未找到元数据表 ${TARGET.databaseName}.${TARGET.tableName}`);
    return r;
}

/** 确保临时测试用户存在（幂等），返回 userId */
async function ensureUser(api: Api, u: { username: string; password: string; roles: string[] }): Promise<string> {
    const existing = psqlSys(`SELECT id FROM sys_user WHERE username = '${u.username}'`);
    if (existing) return existing;
    const user = await api.post<{ id: string }>('/system/users', {
        username: u.username,
        password: u.password,
        roles: u.roles,
        email: `${u.username}@test.io`,
    });
    return String(user.id);
}

/** 播种：清残留 + 复位敏感度 + 创建 3 个临时用户 */
export async function seedF2(): Promise<void> {
    cleanupApisAndKeys(); // 清上次残留，保证列表空态可预测
    resetSensitivity();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    for (const u of Object.values(F2_USERS)) {
        await ensureUser(api, u);
    }
    await api.dispose();
}

// ==================== 清理（afterAll） ====================

/** 物理删除测试 API / Key / 绑定（比软删干净，路径可复用） */
export function cleanupApisAndKeys(): void {
    psqlDs(`
        DELETE FROM api_key_binding
        WHERE key_id IN (SELECT id FROM api_key WHERE name LIKE '${PREFIX}%')
           OR api_id IN (SELECT id FROM data_api WHERE name LIKE '${PREFIX}%');
        DELETE FROM api_key WHERE name LIKE '${PREFIX}%';
        DELETE FROM data_api WHERE name LIKE '${PREFIX}%';
    `);
}

/** 删除临时测试用户 */
export function cleanupUsers(): void {
    for (const u of Object.values(F2_USERS)) {
        psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = '${u.username}')`);
        psqlSys(`DELETE FROM sys_user WHERE username = '${u.username}'`);
    }
}

/** 全量清理：API/Key + 用户 + 敏感度复位 */
export async function cleanupF2(): Promise<void> {
    try {
        cleanupApisAndKeys();
        cleanupUsers();
        resetSensitivity();
    } catch (e) {
        // 清理失败不阻断测试收尾，留待人工处理
        console.warn('[F2 cleanup] 清理异常（可忽略，人工复查）:', e);
    }
}
