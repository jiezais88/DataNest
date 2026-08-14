import {execSync} from 'child_process';
import {expect} from '@playwright/test';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 11 F1 审计日志 E2E 数据辅助（自播种自清理，对齐 sprint10 f2-seed 模式，用户确认 2026-08-14）。
 *
 * 数据策略：
 * - 临时用户/数据源/同步任务/DAG/API/Key 统一 e2e_s11_ 前缀命名，测试产物清理时物理删除。
 * - 审计记录本身只增不改不删（PRD B5/AL-10 产品约束），测试产生的审计记录保留在审计表中，
 *   靠 e2e_s11_ 前缀 + 精确匹配隔离，不依赖全量列表。
 * - 主测试表：内置 Doris（datasourceId=-1）datanest.target_products（元数据齐全、有真实数据）；
 *   敏感度闸门用例直接 UPDATE metadata_table 改级，测完复位 PUBLIC。
 * - 审计写入是异步 fail-open，查询一律用 expect.poll 轮询（findAudit）。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

/** 审计权限隔离用例用分析师（独立账号，审计权限验证 + 测试后清理） */
export const ANALYST = {username: 'e2e_s11_analyst', password: 'Test123456', roles: ['DATA_ANALYST']};

/** 测试数据命名前缀 */
export const PREFIX = 'e2e_s11_';
/** 数据 API 路径段前缀（路径段规则：^[a-z0-9][a-z0-9-_]{0,99}$） */
export const PATH_PREFIX = 'e2e-s11-';

/** 内置 Doris 测试表 */
export const TARGET = {
    datasourceId: '-1',
    databaseName: 'datanest',
    tableName: 'target_products',
} as const;

// ==================== DB 直连辅助（docker exec -i 传 stdin，Windows 引号安全） ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

/** 系统库（用户 / audit_log） */
export const psqlSys = (sql: string): string => psql('datanest_system', sql);
/** 工程库（数据源 / 同步任务 / DAG） */
export const psqlEng = (sql: string): string => psql('datanest_engineering', sql);
/** 治理库（元数据敏感度） */
export const psqlGov = (sql: string): string => psql('datanest_governance', sql);
/** 数据服务库（data_api / api_key） */
export const psqlDs = (sql: string): string => psql('datanest_dataservice', sql);

// ==================== 用户 ====================

/** 确保临时测试用户存在（幂等），返回 userId */
export async function ensureUser(api: Api, u: {username: string; password: string; roles: string[]}): Promise<string> {
    const existing = psqlSys(`SELECT id FROM sys_user WHERE username = '${u.username}'`);
    if (existing) return existing;
    const user = await api.post<{id: string}>('/system/users', {
        username: u.username,
        password: u.password,
        roles: u.roles,
        email: `${u.username}@test.io`,
    });
    return String(user.id);
}

/** 物理删除 e2e_s11_ 前缀的临时用户 */
export function cleanupUsers(): void {
    psqlSys(`
        DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%');
        DELETE FROM sys_user WHERE username LIKE '${PREFIX}%';
    `);
}

// ==================== 敏感度（target_products） ====================

/** 读取 target_products 的 metadata_table id */
export function getTableId(): string {
    const r = psqlGov(
        `SELECT id FROM metadata_table
         WHERE datasource_id=${TARGET.datasourceId} AND database_name='${TARGET.databaseName}' AND table_name='${TARGET.tableName}'`,
    );
    if (!r) throw new Error(`未找到元数据表 ${TARGET.databaseName}.${TARGET.tableName}`);
    return r;
}

/** 直接改级（敏感度闸门造数）：PUBLIC | INTERNAL | CONFIDENTIAL */
export function setSensitivity(level: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL'): void {
    psqlGov(
        `UPDATE metadata_table SET sensitivity_level='${level}'
         WHERE datasource_id=${TARGET.datasourceId} AND database_name='${TARGET.databaseName}' AND table_name='${TARGET.tableName}'`,
    );
}

/** 复位为 PUBLIC（幂等） */
export function resetSensitivity(): void {
    setSensitivity('PUBLIC');
}

// ==================== 审计查询辅助（API 辅助诊断） ====================

export interface AuditFindQuery {
    opType?: string;
    resourceType?: string;
    /** 匹配资源名 / 内容 / 失败原因（模糊） */
    keyword?: string;
    operatorName?: string;
    result?: 'SUCCESS' | 'FAILURE';
}

/** 近 6 小时时间窗的 ISO 起止（审计查询时间参数格式，对齐后端 LocalDateTime.parse） */
function recentRange(): {startTime: string; endTime: string} {
    const pad = (n: number) => String(n).padStart(2, '0');
    const fmt = (d: Date) =>
        `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    return {
        startTime: fmt(new Date(Date.now() - 6 * 60 * 60 * 1000)),
        endTime: fmt(new Date(Date.now() + 60 * 1000)),
    };
}

/**
 * 轮询查找审计记录（审计异步落库，fail-open，最多等 timeoutMs）。
 * 返回第一条满足条件的完整记录；超时抛断言错误。
 */
export async function findAudit(api: Api, q: AuditFindQuery, timeoutMs = 20_000): Promise<any> {
    let found: any | null = null;
    const {startTime, endTime} = recentRange();
    await expect.poll(async () => {
        const params = new URLSearchParams({page: '1', pageSize: '100', startTime, endTime});
        if (q.operatorName) params.set('operatorName', q.operatorName);
        if (q.opType) params.set('opType', q.opType);
        if (q.resourceType) params.set('resourceType', q.resourceType);
        if (q.keyword) params.set('keyword', q.keyword);
        const page = await api.get<{records: any[]}>(`/system/audit-logs?${params.toString()}`);
        found = page.records.find((r) =>
            (!q.opType || r.opType === q.opType) &&
            (!q.resourceType || r.resourceType === q.resourceType) &&
            (!q.result || r.result === q.result) &&
            (!q.keyword || [r.resourceName, r.content, r.errorMessage].some((v: string | null) => v != null && v.includes(q.keyword!))),
        ) ?? null;
        return found !== null;
    }, {timeout: timeoutMs, intervals: [1000], message: `审计记录未出现: ${JSON.stringify(q)}`}).toBe(true);
    return found;
}

// ==================== 测试产物清理 ====================

/** 物理删除 e2e_s11_ 前缀的数据源 / 同步任务 / 采集任务 */
export function cleanupEngineeringData(): void {
    // 同步任务（先删日志/历史，避免外键）
    const jobIds = psqlEng(`SELECT id FROM sync_job WHERE name LIKE '${PREFIX}%'`).split('\n').filter(Boolean);
    if (jobIds.length > 0) {
        psqlEng(`DELETE FROM sync_job_log WHERE sync_job_id IN (${jobIds.join(',')})`);
        psqlEng(`DELETE FROM sync_job_history WHERE sync_job_id IN (${jobIds.join(',')})`);
        psqlEng(`DELETE FROM sync_job WHERE id IN (${jobIds.join(',')})`);
    }
    // 数据源
    psqlEng(`DELETE FROM datasource WHERE name LIKE '${PREFIX}%'`);
    // 采集任务（collect_task，若存在该表）
    try {
        psqlEng(`DELETE FROM collect_task WHERE name LIKE '${PREFIX}%'`);
    } catch {
        // 表不存在则忽略
    }
}

/** 经 API 删除 e2e_s11_ 前缀的 DAG + 项目（级联删 node/edge/execution） */
export async function cleanupDags(api: Api): Promise<void> {
    const projects = psqlEng(`SELECT id, name FROM dag_project WHERE name LIKE '${PREFIX}%'`).split('\n').filter(Boolean);
    for (const line of projects) {
        const pid = line.split('|')[0];
        const dags = await api.get<any[]>(`/engineering/dev/dags?projectId=${pid}`);
        for (const d of dags ?? []) {
            try {
                await api.del(`/engineering/dev/dags/${d.id}`);
            } catch (e) {
                console.warn('[s11 cleanup] 删除 DAG 失败（可忽略）:', d.id, e);
            }
        }
        try {
            await api.del(`/engineering/dev/dag-projects/${pid}`);
        } catch (e) {
            console.warn('[s11 cleanup] 删除项目失败（可忽略）:', pid, e);
        }
    }
}

/** 物理删除 e2e_s11_ 前缀的数据 API / Key（对齐 f2-seed cleanupApisAndKeys） */
export function cleanupApisAndKeys(): void {
    psqlDs(`
        DELETE FROM api_key_binding
        WHERE key_id IN (SELECT id FROM api_key WHERE name LIKE '${PREFIX}%')
           OR api_id IN (SELECT id FROM data_api WHERE name LIKE '${PREFIX}%');
        DELETE FROM api_key WHERE name LIKE '${PREFIX}%';
        DELETE FROM data_api WHERE name LIKE '${PREFIX}%';
    `);
}

/** 全量清理：工程数据 + DAG + API/Key + 用户 + 敏感度复位（幂等，失败不阻断收尾） */
export async function cleanupS11(): Promise<void> {
    try {
        cleanupEngineeringData();
    } catch (e) {
        console.warn('[s11 cleanup] engineering 数据清理异常:', e);
    }
    try {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        await cleanupDags(api);
        await api.dispose();
    } catch (e) {
        console.warn('[s11 cleanup] DAG 清理异常:', e);
    }
    try {
        cleanupApisAndKeys();
    } catch (e) {
        console.warn('[s11 cleanup] API/Key 清理异常:', e);
    }
    try {
        cleanupUsers();
    } catch (e) {
        console.warn('[s11 cleanup] 用户清理异常:', e);
    }
    try {
        resetSensitivity();
    } catch (e) {
        console.warn('[s11 cleanup] 敏感度复位异常:', e);
    }
}
