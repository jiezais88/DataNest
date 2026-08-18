import {expect, request as pwRequest, test, type APIRequestContext} from '@playwright/test';
import {Api, API_BASE} from '../../sprint6/helpers/api';
import {
    ADMIN,
    IDP_BASE,
    LDAP_USER,
    OIDC_AUTHORIZE_URL,
    PW,
    PREFIX,
    WEAK_PW,
    cleanupUsers,
    forcePasswordExpired,
    psqlSys,
    queryUser,
    seedUsers,
} from '../e2e/helpers/seed';

/**
 * Sprint 14 SSO API 级测试（辅助诊断）。
 *
 * 覆盖 PRD AC-1~9 / N-1~3：SSO 状态/配置权限矩阵、密码复杂度/过期/失败锁定、
 * 登录模式（sso-only + admin 保底）、OIDC 授权码全链路/自动建号/自动绑定/state 防 CSRF、
 * 角色映射命中/未命中、LDAP 登录/同步、解绑。
 *
 * 环境约定：Gateway http://localhost:8080/api；SSO 运行时配置已启用（enabled=true, mixed）。
 * 全局配置（mode/role-mapping/password-policy）临时改动在 finally 恢复。
 * 串行执行。
 */

test.describe.configure({mode: 'serial'});

let admin: Api;
let engineer: Api;
let lockId = '';
let bindId = '';
let bindNewPwd = 'E2eTest@456';

test.beforeAll(async () => {
    await seedUsers();
    admin = await Api.create();
    await admin.login(ADMIN.username, ADMIN.password);
    engineer = await Api.create();
    await engineer.login(`${PREFIX}engineer`, PW);
    // 预取 e2e_s14_lock / e2e_s14_bind 的 userId
    const locks = await admin.get<{records: {id: string}[]}>('/system/users?page=1&pageSize=20&keyword=e2e_s14_lock');
    lockId = locks.records[0].id;
    const binds = await admin.get<{records: {id: string}[]}>('/system/users?page=1&pageSize=20&keyword=e2e_s14_bind');
    bindId = binds.records[0].id;
});

test.afterAll(async () => {
    await admin?.dispose();
    await engineer?.dispose();
    cleanupUsers();
});

// ==================== OIDC 授权码全链路辅助 ====================

/** 模拟浏览器完成 OIDC 授权码流程，返回 sa-token + /auth/me 数据 */
async function oidcLoginAsIdpUser(idpUser: string): Promise<{token: string; me: any}> {
    const ctx = await pwRequest.newContext({maxRedirects: 0});
    try {
        // 1) 发起授权 → 302 IdP authorize（带 state）
        const r1 = await ctx.get(OIDC_AUTHORIZE_URL);
        expect(r1.status()).toBe(302);
        const loc1 = r1.headers()['location'];
        expect(loc1).toBeTruthy();
        // 2) IdP 授权页（mock-idp 用 user 参数选用户）→ 302 callback?code&state
        const authUrl = loc1 + (loc1.includes('?') ? '&' : '?') + `user=${idpUser}`;
        const r2 = await ctx.get(authUrl);
        expect(r2.status()).toBe(302);
        const loc2 = r2.headers()['location'];
        expect(loc2).toContain('/oidc/callback');
        // 3) callback → 校验 state → 换 token → 302 /login#ssoToken
        const r3 = await ctx.get(loc2);
        const loc3 = r3.headers()['location'];
        expect(loc3).toMatch(/#ssoToken=/);
        const token = loc3.split('#ssoToken=')[1];
        // 4) 用 ssoToken 验证会话
        const meRes = await ctx.get(`${API_BASE}/system/auth/me`, {headers: {Authorization: token}});
        const me = await meRes.json();
        expect(me.code).toBe(200);
        return {token, me: me.data};
    } finally {
        await ctx.dispose();
    }
}

/** 清空 mock 用户 dave 的平台账号（subject 与用户名） */
function purgeDave(): void {
    psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username='dave');
             DELETE FROM sys_user WHERE username='dave';`);
}

// ==================== A. 状态与配置 ====================

test('SA-01 SSO 状态契约（公开接口）', async () => {
    const ctx = await pwRequest.newContext();
    try {
        const res = await ctx.get(`${API_BASE}/system/auth/sso/status`);
        const env = await res.json();
        expect(env.code).toBe(200);
        expect(env.data).toMatchObject({enabled: true, mode: 'mixed', oidcEnabled: true, ldapEnabled: true});
    } finally {
        await ctx.dispose();
    }
});

test('SA-02 配置读权限矩阵（admin 可读 / engineer 403）', async () => {
    const cfg = await admin.get('/system/auth/sso/config');
    expect(cfg.enabled).toBe(true);
    expect(cfg.oidc.enabled).toBe(true);
    expect(cfg.ldap.enabled).toBe(true);
    const env = await engineer.raw('GET', '/system/auth/sso/config');
    expect(env.code).toBe(1005);
});

test('SA-03 配置保存热生效（改 minLength 后 GET 立即可见，测后恢复）', async () => {
    const origin = await admin.get('/system/auth/sso/config');
    try {
        await admin.put('/system/auth/sso/config', {...origin, passwordPolicy: {...origin.passwordPolicy, minLength: 10}});
        await expect.poll(async () => (await admin.get('/system/auth/sso/config')).passwordPolicy.minLength, {timeout: 10_000}).toBe(10);
    } finally {
        await admin.put('/system/auth/sso/config', {...origin, passwordPolicy: {...origin.passwordPolicy, minLength: 8}});
        await expect.poll(async () => (await admin.get('/system/auth/sso/config')).passwordPolicy.minLength, {timeout: 10_000}).toBe(8);
    }
});

// ==================== B. 密码策略 ====================

test('SA-04 复杂度校验：弱密码创建被拒 1007，合规密码成功', async () => {
    // 弱密码：8 位全小写（无大写无数字）→ 1007
    const weak = await admin.raw('POST', '/system/users', {
        username: `${PREFIX}weak_tmp`, password: WEAK_PW, roles: ['DATA_ANALYST'], email: `${PREFIX}weak_tmp@test.io`,
    });
    expect(weak.code).toBe(1007);
    expect(weak.message).toContain('复杂度');
    // 合规密码：创建成功
    const ok = await admin.raw('POST', '/system/users', {
        username: `${PREFIX}ok_tmp`, password: PW, roles: ['DATA_ANALYST'], email: `${PREFIX}ok_tmp@test.io`,
    });
    expect(ok.code).toBe(200);
    // 清理临时用户
    psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%_tmp');
             DELETE FROM sys_user WHERE username LIKE '${PREFIX}%_tmp';`);
});

test('SA-05 失败锁定：5 次错误 → 锁定（正确密码也 1008）→ admin 解锁 → 恢复登录', async () => {
    const bad = {username: `${PREFIX}lock`, password: 'WrongPass1', rememberMe: false};
    // 连续 5 次错误密码：每次返回 1002（用户名或密码错误），失败计数递增
    for (let i = 1; i <= 5; i++) {
        const env = await admin.raw('POST', '/system/auth/login', bad);
        expect(env.code).toBe(1002, `第 ${i} 次错误密码应返回 1002，实际 ${env.code}`);
    }
    // 已锁定：即使正确密码也返回 1008
    const locked = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}lock`, password: PW, rememberMe: false});
    expect(locked.code).toBe(1008);
    expect(queryUser(`${PREFIX}lock`)?.lockedUntil).toBeTruthy();
    // admin 解锁
    const unlocked = await admin.put(`/system/users/${lockId}/unlock`);
    expect(unlocked.username).toBe(`${PREFIX}lock`);
    expect(queryUser(`${PREFIX}lock`)?.lockedUntil).toBe('');
    // 恢复登录
    const ok = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}lock`, password: PW, rememberMe: false});
    expect(ok.code).toBe(200);
});

test('SA-06 密码过期：登录 mustChangePwd=true → 强制改密 → 新密码登录 mustChangePwd=false', async () => {
    forcePasswordExpired(`${PREFIX}expire`);
    // 过期登录：code 200 + userInfo.mustChangePwd=true（前端据此跳强制改密页）
    const login = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}expire`, password: PW, rememberMe: false});
    expect(login.code).toBe(200);
    expect(login.data.userInfo.mustChangePwd).toBe(true);
    // 强制改密（用登录返回 token 调改密接口，Result<Void> data 为 null）
    const api = await Api.create();
    api.token = login.data.token;
    try {
        const change = await api.raw('PUT', '/system/users/password',
            {oldPassword: PW, newPassword: bindNewPwd, confirmNewPassword: bindNewPwd});
        expect(change.code).toBe(200);
    } finally {
        await api.dispose();
    }
    // 新密码登录：mustChangePwd=false，过期时间已重置
    const relogin = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}expire`, password: bindNewPwd, rememberMe: false});
    expect(relogin.code).toBe(200);
    expect(relogin.data.userInfo.mustChangePwd).toBe(false);
    expect(queryUser(`${PREFIX}expire`)?.passwordExpireAt).toBeTruthy();
});

// ==================== C. 登录模式 ====================

test('SA-07 仅 SSO 模式：普通用户本地登录 1013，admin 保底 200，恢复 mixed', async () => {
    const origin = await admin.get('/system/auth/sso/config');
    try {
        await admin.put('/system/auth/sso/config', {...origin, mode: 'sso-only'});
        // 普通用户本地登录被拒
        const refused = await admin.raw('POST', '/system/auth/login',
            {username: `${PREFIX}engineer`, password: PW, rememberMe: false});
        expect(refused.code).toBe(1013);
        // admin 逃生通道
        const adminLogin = await admin.raw('POST', '/system/auth/login',
            {username: 'admin', password: 'admin123', rememberMe: false});
        expect(adminLogin.code).toBe(200);
    } finally {
        await admin.put('/system/auth/sso/config', {...origin, mode: 'mixed'});
    }
    // 恢复后普通用户可本地登录
    const ok = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}engineer`, password: PW, rememberMe: false});
    expect(ok.code).toBe(200);
});

// ==================== D. OIDC ====================

test('SA-08 OIDC 授权码全链路（已绑定 subject 直接登录）', async () => {
    // dave 当前为开发自测已建号用户（subject=mock-sub-dave-004）→ 第一分支直接登录
    const {me} = await oidcLoginAsIdpUser('dave');
    expect(me.username).toBe('dave');
    expect(me.roles).toContain('DATA_ENGINEER');
});

test('SA-09 OIDC 自动建号 + 角色映射（清库后重建）', async () => {
    purgeDave();
    const {me} = await oidcLoginAsIdpUser('dave');
    expect(me.username).toBe('dave');
    // groups=[datanest-engineers] 命中规则 → DATA_ENGINEER
    expect(me.roles).toContain('DATA_ENGINEER');
    const row = queryUser('dave');
    expect(row?.authSource).toBe('OIDC');
    expect(row?.ssoSubject).toBe('mock-sub-dave-004');
});

test('SA-10 OIDC 自动绑定已有账号 + 企业身份账号本地登录 1014', async () => {
    // 清理 dave + 本地预置 e2e_s14_bind（email 指向 dave@datanest.local）
    purgeDave();
    await admin.put(`/system/users/${bindId}`, {email: 'dave@datanest.local'});
    const {me} = await oidcLoginAsIdpUser('dave');
    // 未命中 subject → email 命中 e2e_s14_bind → 自动绑定
    expect(me.username).toBe(`${PREFIX}bind`);
    const row = queryUser(`${PREFIX}bind`);
    expect(row?.authSource).toBe('OIDC');
    expect(row?.ssoSubject).toBe('mock-sub-dave-004');
    // 企业身份账号不允许本地密码登录
    const local = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}bind`, password: PW, rememberMe: false});
    expect(local.code).toBe(1014);
});

test('SA-11 OIDC state 防 CSRF：错误 state 返回 1019', async () => {
    // 走一次完整流程拿到合法 code，再用错误 state 调 callback
    const ctx = await pwRequest.newContext({maxRedirects: 0});
    try {
        const r1 = await ctx.get(OIDC_AUTHORIZE_URL);
        const loc1 = r1.headers()['location'];
        const r2 = await ctx.get(loc1 + '&user=dave');
        const cbUrl = r2.headers()['location'];
        const wrongStateUrl = cbUrl.replace(/state=[^&]+/, 'state=forged-state-000');
        const r3 = await ctx.get(wrongStateUrl);
        const env = await r3.json();
        expect(env.code).toBe(1019);
    } finally {
        await ctx.dispose();
    }
});

test('SA-12 解绑：恢复 LOCAL + 本地登录恢复；再次 OIDC 自动重绑（可重入）', async () => {
    // 前置：e2e_s14_bind 处于 OIDC 绑定（SA-10）
    const before = queryUser(`${PREFIX}bind`);
    expect(before?.authSource).toBe('OIDC');
    // 解绑
    const unbind = await admin.put(`/system/users/${bindId}/unbind-sso`);
    expect(unbind.authSource).toBe('LOCAL');
    const after = queryUser(`${PREFIX}bind`);
    expect(after?.authSource).toBe('LOCAL');
    expect(after?.ssoSubject).toBe('');
    // 解绑后本地密码登录恢复（e2e_s14_bind 仍是 LOCAL 密码用户）
    const local = await admin.raw('POST', '/system/auth/login',
        {username: `${PREFIX}bind`, password: PW, rememberMe: false});
    expect(local.code).toBe(200);
    // 再次 OIDC 登录 → subject 已清 → email 仍命中 → 自动重绑（绑定机制可重入）
    const {me} = await oidcLoginAsIdpUser('dave');
    expect(me.username).toBe(`${PREFIX}bind`);
    expect(queryUser(`${PREFIX}bind`)?.authSource).toBe('OIDC');
});

test('SA-13 角色映射未命中 → 默认角色（DATA_ANALYST），规则可配置热生效', async () => {
    const origin = await admin.get('/system/auth/sso/config');
    try {
        // 临时清空映射规则 + 清掉 dave 与 e2e_s14_bind（避免 subject 命中）
        purgeDave();
        psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username='${PREFIX}bind');
                 DELETE FROM sys_user WHERE username='${PREFIX}bind';`);
        await admin.put('/system/auth/sso/config', {...origin, roleMapping: {...origin.roleMapping, rules: []}});
        // dave 自动建号：groups 命中规则为空 → 未命中 → 默认角色
        const {me} = await oidcLoginAsIdpUser('dave');
        expect(me.username).toBe('dave');
        expect(me.roles).toContain('DATA_ANALYST');
    } finally {
        // 恢复规则
        await admin.put('/system/auth/sso/config', {...origin, roleMapping: {...origin.roleMapping, rules: origin.roleMapping.rules}});
    }
    // 恢复规则后 dave 重新登录：subject 命中 → 保留当前角色；重建为映射角色需要清库重登
    purgeDave();
    const {me} = await oidcLoginAsIdpUser('dave');
    expect(me.username).toBe('dave');
    expect(me.roles).toContain('DATA_ENGINEER');
});

// ==================== E. LDAP ====================

test('SA-14 LDAP 域账号登录成功 + 角色映射', async () => {
    const ctx = await pwRequest.newContext();
    try {
        const res = await ctx.post(`${API_BASE}/system/auth/sso/ldap/login`, {
            data: {username: LDAP_USER.username, password: LDAP_USER.password},
        });
        const env = await res.json();
        expect(env.code).toBe(200);
        expect(env.data.userInfo.username).toBe('zhangsan');
        expect(env.data.userInfo.roles).toContain('DATA_ENGINEER');
    } finally {
        await ctx.dispose();
    }
});

test('SA-15 LDAP 域账号密码错误 → 1017', async () => {
    const ctx = await pwRequest.newContext();
    try {
        const res = await ctx.post(`${API_BASE}/system/auth/sso/ldap/login`, {
            data: {username: LDAP_USER.username, password: 'WrongDomain@1'},
        });
        const env = await res.json();
        expect(env.code).toBe(1017);
    } finally {
        await ctx.dispose();
    }
});

test('SA-16 LDAP 用户同步（total=2, created=0, updated=2）', async () => {
    const res = await admin.post<{total: number; created: number; updated: number; skipped: number}>('/system/auth/sso/ldap/sync');
    expect(res.total).toBe(2);
    expect(res.created).toBe(0);
    expect(res.updated).toBe(2);
    expect(res.skipped).toBe(0);
});

test('SA-17 LDAP 同步权限：engineer 无 auth:sync → 403', async () => {
    const env = await engineer.raw('POST', '/system/auth/sso/ldap/sync');
    expect(env.code).toBe(1005);
});
