import {Api} from './api';
import {psql} from './db';
import {ADMIN, TEST_USERS, TPL_PREFIX} from './data';

/**
 * Sprint 6 规则模板库测试数据播种/清理。
 * 所有函数幂等：重复执行不会产生重复数据。
 *
 * 注意：
 * - 内置四类模板由 Flyway V3.6.0 迁移脚本插入，本模块不负责播种内置模板，
 *   仅清理/断言它们存在。
 * - 自定义测试模板统一使用 e2e_s6 前缀命名，便于播种与清理。
 */

const ERR = (e: unknown) => String(e).slice(0, 300);

// ==================== 用户 ====================

/** 确保测试用户存在（幂等），返回 userId */
export async function ensureUser(api: Api, u: {
    username: string;
    password: string;
    roles: string[];
    email: string
}): Promise<string> {
    const existing = psql(`SELECT id
                           FROM sys_user
                           WHERE username = '${u.username}'`);
    if (existing) {
        if (u.email) {
            psql(`UPDATE sys_user
                  SET email='${u.email}'
                  WHERE username = '${u.username}'`);
        }
        return existing;
    }
    const user = await api.post('/system/users', {
        username: u.username,
        password: u.password,
        roles: u.roles,
        email: u.email,
    });
    return String(user.id);
}

export async function ensureTestUsers(): Promise<Record<string, string>> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const ids: Record<string, string> = {};
    for (const [key, u] of Object.entries(TEST_USERS)) {
        ids[key] = await ensureUser(api, u);
    }
    await api.dispose();
    return ids;
}

export async function deleteTestUsers(): Promise<void> {
    for (const u of Object.values(TEST_USERS)) {
        psql(`DELETE
              FROM sys_user_role
              WHERE user_id IN (SELECT id FROM sys_user WHERE username = '${u.username}')`);
        psql(`DELETE
              FROM sys_user
              WHERE username = '${u.username}'`);
    }
}

// ==================== 规则模板 ====================

/**
 * 播种规则模板测试数据（幂等）：
 * 先清掉历史 e2e_s6 前缀模板，再插入若干自定义模板。
 * 内置四类模板由迁移脚本保证存在，这里不动。
 */
export function seedTemplates(): void {
    psql(`DELETE
          FROM quality_rule_template
          WHERE name LIKE '${TPL_PREFIX}%'`);

    const insert = `
        INSERT INTO quality_rule_template
        (name, type, description, sql_template, result_metric, builtin, enabled, created_by, updated_by)
        VALUES ('${TPL_PREFIX}_完整性', 'COMPLETENESS', 'e2e s6 自定义完整性模板',
                'SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}',
                'null_rate', 0, 1, 0, 0),
               ('${TPL_PREFIX}_唯一性', 'UNIQUENESS', 'e2e s6 自定义唯一性模板',
                'SELECT COUNT(*) - COUNT(DISTINCT {column}) AS duplicate_count FROM {table}',
                'duplicate_count', 0, 1, 0, 0),
               ('${TPL_PREFIX}_停用模板', 'RANGE', 'e2e s6 自定义停用模板',
                'SELECT COUNT(*) AS total FROM {table}',
                'out_of_range_rate', 0, 0, 0, 0);
    `;
    psql(insert);
}

/** 清理规则模板测试数据（仅 e2e_s6 前缀，不动内置） */
export function cleanupTemplates(): void {
    psql(`DELETE
          FROM quality_rule_template
          WHERE name LIKE '${TPL_PREFIX}%'`);
}

// ==================== 清理 / 播种 ====================

/** 清理全部 Sprint 6 测试数据（幂等） */
export async function cleanupAll(): Promise<void> {
    try {
        deleteTestUsers();
    } catch (e) {
        console.warn('sprint6 cleanup users:', ERR(e));
    }
    try {
        cleanupTemplates();
    } catch (e) {
        console.warn('sprint6 cleanup templates:', ERR(e));
    }
}

/** 全量播种（globalSetup 调用，幂等） */
export async function seedAll(): Promise<void> {
    await ensureTestUsers();
    seedTemplates();
}
