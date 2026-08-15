import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 11 F2 角色权限 E2E 测试数据辅助：自播种自清理（用户确认 2026-08-15）。
 *
 * 范围：角色管理（PM-7~15）+ 权限点体系（PM-16）+ 数据权限五入口（PM-1/2/4/5）+
 * 权限树机密表锁定（PM-6）+ 保存即时生效（PM-14）+ 快捷档位（PM-17）+ 权限配置审计（PM-18）。
 *
 * 数据策略：
 * - 临时自定义角色 e2e_s11f2_role（自定义，仅授权 datasource:view / sql:execute / asset:view / api:create）
 * - 临时用户 e2e_s11f2_user（绑定自定义角色，数据权限 WHITELIST 白名单 mysql.testdb.users）
 * - 机密锁测试表：mysql 数据源 testdb.users 临时改 CONFIDENTIAL 验证权限树锁定，测完恢复 PUBLIC
 * - 测试资源统一 e2e_s11f2_ 前缀，cleanup 物理清理（含既有残留）。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

/** 白名单测试：mysql 数据源 testdb（有真实数据，SQL 可执行） */
export const MYSQL = {
    datasourceId: '2083088527209295874',
    database: 'testdb',
    table: 'users',          // 有权限表（SELECT 可查 2 行）
    forbiddenTable: 'orders', // 无权限表（2012）
} as const;

/** 机密锁测试表：mysql.testdb.users（临时改 CONFIDENTIAL → 权限树锁定 → 恢复 PUBLIC） */
export const CONF_TABLE_ID = '2083088529775558657';

export const PREFIX = 'e2e_s11f2_';

// ==================== DB 直连辅助（docker exec -i 传 stdin） ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

export const psqlSys = (sql: string): string => psql('datanest_system', sql);
export const psqlGov = (sql: string): string => psql('datanest_governance', sql);
export const psqlEng = (sql: string): string => psql('datanest_engineering', sql);

// ==================== 播种（beforeAll） ====================

/** 快照机密测试表敏感度（改级前保留原级别） */
export function snapshotConfSensitivity(): string {
    const r = psqlGov(`SELECT sensitivity_level FROM metadata_table WHERE id=${CONF_TABLE_ID}`);
    return r || 'PUBLIC';
}

export function setConfSensitivity(level: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL'): void {
    psqlGov(`UPDATE metadata_table SET sensitivity_level='${level}' WHERE id=${CONF_TABLE_ID}`);
}

/**
 * 播种：清理残留 → 创建自定义角色（权限点最小集）→ 创建测试用户并绑定 → 设置数据权限白名单。
 * 返回 {roleId, userId}，供测试用例使用。
 */
export async function seedF2(): Promise<{roleId: string; userId: string}> {
    cleanupPhysical();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);

    const roleName = `${PREFIX}role`;
    const roleCode = `E2E_S11F2_ROLE`;
    // 删除上次残留的同 code 角色（有绑定先解绑）
    psqlSys(`
        DELETE FROM sys_user_role WHERE role_id IN (SELECT id FROM sys_role WHERE code='${roleCode}');
        DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code='${roleCode}');
        DELETE FROM sys_data_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code='${roleCode}');
        DELETE FROM sys_role WHERE code='${roleCode}';
    `);
    const role = await api.post<{id: string; permissions: string[]}>('/system/roles', {
        name: roleName,
        code: roleCode,
        description: 'Sprint11 F2 E2E 自定义角色',
        permissions: ['datasource:view', 'sql:execute', 'asset:view', 'api:create', 'sync:create', 'sync:view'],
    });
    const roleId = String(role.id);

    // 创建用户并绑定该角色
    const userName = `${PREFIX}user`;
    psqlSys(`DELETE FROM sys_user WHERE username='${userName}'`);
    const user = await api.post<{id: string}>('/system/users', {
        username: userName,
        password: 'Test123456',
        roles: [roleCode],
        email: `${userName}@test.io`,
    });
    const userId = String(user.id);

    // 数据权限：WHITELIST 白名单 mysql.testdb.users（角色维度）
    await api.post('/system/data-permissions', {
        roleId,
        dataScope: 'WHITELIST',
        grants: [{datasourceId: MYSQL.datasourceId, databaseName: MYSQL.database, tableName: MYSQL.table}],
    });

    await api.dispose();
    return {roleId, userId};
}

// ==================== 清理（afterAll） ====================

/** 物理清理全部 e2e_s11f2_ 前缀资源 + 机密测试表敏感度复位 */
export function cleanupPhysical(): void {
    psqlSys(`
        DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%');
        DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE name LIKE '${PREFIX}%' OR code LIKE 'E2E_S11F2%' OR code LIKE 'E2EF2%');
        DELETE FROM sys_data_permission WHERE role_id IN (SELECT id FROM sys_role WHERE name LIKE '${PREFIX}%' OR code LIKE 'E2E_S11F2%' OR code LIKE 'E2EF2%');
        DELETE FROM sys_user WHERE username LIKE '${PREFIX}%';
        DELETE FROM sys_role WHERE name LIKE '${PREFIX}%' OR code LIKE 'E2E_S11F2%' OR code LIKE 'E2EF2%';
    `);
    psqlGov(`UPDATE metadata_table SET sensitivity_level='PUBLIC' WHERE id=${CONF_TABLE_ID}`);
    psqlEng(`DELETE FROM datasource_connection WHERE name LIKE '${PREFIX}%'`);
}

export async function cleanupF2(): Promise<void> {
    try {
        cleanupPhysical();
    } catch (e) {
        console.warn('[f2 cleanup] 清理异常（可忽略，人工复查）:', e);
    }
}
