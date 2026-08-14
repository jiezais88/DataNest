import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 11 F1 审计日志 E2E 测试数据辅助：自播种自清理（用户确认 2026-08-14）。
 *
 * 范围：8 类操作埋点（用户/数据源/同步任务/DAG/SQL/数据API/APIKey/分级）+ 审计查询页 UI。
 *
 * 数据策略：
 * - 临时用户 e2e_s11_analyst（DATA_ANALYST），用于 SQL 机密拦截 + 权限测试（审计页仅超管）。
 * - 临时数据源 e2e_s11_audit_ds（MYSQL → middleware-test-mysql/testdb），触发 DATASOURCE 埋点。
 * - 机密拦截/改级测试表：datanest.target_users（PUBLIC），临时改 CONFIDENTIAL 造数据，
 *   测完恢复 PUBLIC（选 target_users 而非 target_products：后者被 PUBLISHED 的 f3smoke API 绑定，
 *   改机密会联动下线线上 API，副作用不可接受）。
 * - 测试资源统一 e2e_s11_ 前缀，cleanup 物理清理（含既有残留）。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

/** 内置 Doris（datasourceId=-1）datanest 库 target_users：改级/机密拦截测试表 */
export const SENS = {
    tableId: '2083905047696232450',
    database: 'datanest',
    table: 'target_users',
    datasourceId: '-1',
} as const;

/** 内置 Doris ods.users（PUBLIC，有 3 行数据）：SQL 成功执行测试 */
export const SQL_OK = {
    datasourceId: -1,
    sql: 'SELECT * FROM ods.users LIMIT 100;',
} as const;

export const PREFIX = 'e2e_s11_';

/** 既有 mysql 数据源（testdb），作同步任务源数据源 */
export const MYSQL_DS_ID = '2083088527209295874';

// ==================== DB 直连辅助（docker exec -i 传 stdin，Windows 引号安全） ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

export const psqlSys = (sql: string): string => psql('datanest_system', sql);
export const psqlGov = (sql: string): string => psql('datanest_governance', sql);
export const psqlEng = (sql: string): string => psql('datanest_engineering', sql);
export const psqlDs = (sql: string): string => psql('datanest_dataservice', sql);

// ==================== 播种（beforeAll） ====================

/** 快照 target_users 敏感度并返回原级别 */
export function snapshotSensitivity(): string {
    const r = psqlGov(
        `SELECT sensitivity_level FROM metadata_table WHERE id=${SENS.tableId}`,
    );
    return r || 'PUBLIC';
}

/** 直接改级（造数）：level=PUBLIC|INTERNAL|CONFIDENTIAL */
export function setSensitivity(level: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL'): void {
    psqlGov(`UPDATE metadata_table SET sensitivity_level='${level}' WHERE id=${SENS.tableId}`);
}

export function getSensitivity(): string {
    const r = psqlGov(`SELECT sensitivity_level FROM metadata_table WHERE id=${SENS.tableId}`);
    return r || 'PUBLIC';
}

/** 确保临时分析师用户存在（幂等），返回 userId */
export async function ensureAnalyst(api: Api): Promise<string> {
    const name = PREFIX + 'analyst';
    const existing = psqlSys(`SELECT id FROM sys_user WHERE username='${name}'`);
    if (existing) return existing;
    const user = await api.post<{ id: string }>('/system/users', {
        username: name,
        password: 'Test123456',
        roles: ['DATA_ANALYST'],
        email: `${name}@test.io`,
    });
    return String(user.id);
}

export async function seedAudit(): Promise<void> {
    cleanupPhysical(); // 先清上次残留（数据源/用户/同步任务/DAG/API/Key），保证列表可预测
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    await ensureAnalyst(api);
    await api.dispose();
}

// ==================== 清理（afterAll） ====================

/** 物理清理全部 e2e_s11_ 前缀资源 + 敏感度复位（比软删干净，可重复执行） */
export function cleanupPhysical(): void {
    // 同步任务/DAG 依赖顺序：先删关联历史/节点，再删主表（无外键约束，直接 DELETE）
    psqlEng(`
        DELETE FROM sync_job_log WHERE sync_job_id IN (SELECT id FROM sync_job WHERE name LIKE '${PREFIX}%');
        DELETE FROM sync_job_history WHERE sync_job_id IN (SELECT id FROM sync_job WHERE name LIKE '${PREFIX}%');
        DELETE FROM sync_job WHERE name LIKE '${PREFIX}%';
    `);
    psqlEng(`
        DELETE FROM node_execution WHERE execution_id IN (
            SELECT id FROM dag_execution WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%'));
        DELETE FROM dag_node WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%');
        DELETE FROM dag_edge WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%');
        DELETE FROM dag_execution WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%');
        DELETE FROM dag WHERE name LIKE '${PREFIX}%';
    `);
    psqlDs(`
        DELETE FROM api_key_binding
        WHERE key_id IN (SELECT id FROM api_key WHERE name LIKE '${PREFIX}%')
           OR api_id IN (SELECT id FROM data_api WHERE name LIKE '${PREFIX}%');
        DELETE FROM api_key WHERE name LIKE '${PREFIX}%';
        DELETE FROM data_api WHERE name LIKE '${PREFIX}%';
    `);
    psqlEng(`DELETE FROM datasource_connection WHERE name LIKE '${PREFIX}%'`);
    psqlSys(`
        DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%');
        DELETE FROM sys_user WHERE username LIKE '${PREFIX}%';
    `);
}

/** 复位 target_users 为 PUBLIC（幂等） */
export function resetSensitivity(): void {
    setSensitivity('PUBLIC');
}

export async function cleanupAudit(): Promise<void> {
    try {
        resetSensitivity();
        cleanupPhysical();
    } catch (e) {
        // 清理失败不阻断测试收尾，留待人工处理
        console.warn('[audit cleanup] 清理异常（可忽略，人工复查）:', e);
    }
}
