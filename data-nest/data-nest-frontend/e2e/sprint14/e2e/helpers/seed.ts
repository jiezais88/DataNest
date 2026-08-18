import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 14 SSO E2E 测试数据辅助：自播种自清理。
 *
 * 数据策略：
 * - 临时用户 e2e_s14_engineer / analyst / lock / expire / bind（DATA_ENGINEER / DATA_ANALYST），
 *   密码统一 E2eTest@123（满足默认密码策略：8 位 + 大小写 + 数字）。
 * - 不创建/删除开发自测的 IdP/LDAP 用户（alice/bob/carol/dave/zhangsan/lisi），
 *   OIDC 自动建号/绑定用例在 spec 内对 dave 做清库重建（收尾问用户清理）。
 * - cleanup 物理删除 e2e_s14_* 用户（含 sys_user_role 关联）。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};
/** 满足默认密码策略（8 位 + 大写 + 小写 + 数字）的测试密码 */
export const PW = 'E2eTest@123';
/** 不满足复杂度的弱密码（8 位全小写，无大写无数字） */
export const WEAK_PW = 'abcdefgh';

export const PREFIX = 'e2e_s14_';

/** Mock OIDC IdP（Sprint 14 测试工具）常量 */
export const IDP_BASE = 'http://host.docker.internal:9040';
export const OIDC_AUTHORIZE_URL = 'http://localhost:8080/api/system/auth/sso/oidc/authorize';
export const OIDC_CALLBACK_BASE = 'http://localhost:8080/api/system/auth/sso/oidc/callback';
/** LDAP 域账号 */
export const LDAP_USER = {username: 'zhangsan', password: 'Zhangsan@123'};

// ==================== DB 直连辅助 ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

export const psqlSys = (sql: string): string => psql('datanest_system', sql);

// ==================== 播种 ====================

/** 创建临时用户（幂等：先物理清残留，再用合规密码重建） */
export async function seedUsers(): Promise<void> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        // 物理清残留（含角色关联）
        psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%');
                 DELETE FROM sys_user WHERE username LIKE '${PREFIX}%';`);
        await api.post('/system/users', {
            username: `${PREFIX}engineer`, password: PW, roles: ['DATA_ENGINEER'], email: `${PREFIX}engineer@test.io`,
        });
        await api.post('/system/users', {
            username: `${PREFIX}analyst`, password: PW, roles: ['DATA_ANALYST'], email: `${PREFIX}analyst@test.io`,
        });
        await api.post('/system/users', {
            username: `${PREFIX}lock`, password: PW, roles: ['DATA_ANALYST'], email: `${PREFIX}lock@test.io`,
        });
        await api.post('/system/users', {
            username: `${PREFIX}expire`, password: PW, roles: ['DATA_ANALYST'], email: `${PREFIX}expire@test.io`,
        });
        await api.post('/system/users', {
            username: `${PREFIX}bind`, password: PW, roles: ['DATA_ANALYST'], email: `${PREFIX}bind@test.io`,
        });
    } finally {
        await api.dispose();
    }
}

/** 清理临时用户（物理删除 + 角色关联） */
export function cleanupUsers(): void {
    psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%');
             DELETE FROM sys_user WHERE username LIKE '${PREFIX}%';`);
}

/** 查询用户 auth_source / sso_subject / login_fail_count / locked_until（DB 断言用） */
export function queryUser(username: string):
    { authSource: string; ssoSubject: string; failCount: number; lockedUntil: string; passwordExpireAt: string } | null {
    const row = psqlSys(
        `SELECT COALESCE(auth_source,''), COALESCE(sso_subject,''), COALESCE(login_fail_count,0), COALESCE(locked_until::text,''), COALESCE(password_expire_at::text,'') FROM sys_user WHERE username='${username}'`);
    if (!row) return null;
    const [authSource, ssoSubject, failCount, lockedUntil, passwordExpireAt] = row.split('|');
    return {authSource, ssoSubject, failCount: Number(failCount), lockedUntil, passwordExpireAt};
}

/** 置密码过期（造数用），返回置数行数 */
export function forcePasswordExpired(username: string): number {
    return Number(psqlSys(
        `UPDATE sys_user SET password_expire_at = now() - interval '1 day' WHERE username='${username}' RETURNING 1;`).split('\n').filter(Boolean).length);
}
