import {expect, request as pwRequest, test, type Page} from '@playwright/test';
import {API_BASE} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {
    ADMIN,
    LDAP_USER,
    OIDC_AUTHORIZE_URL,
    PW,
    PREFIX,
    cleanupUsers,
    forcePasswordExpired,
    psqlSys,
    queryUser,
    seedUsers,
} from './helpers/seed';

/**
 * Sprint 14 SSO UI E2E：登录页企业身份入口 / OIDC 全链路 / AD 域登录 /
 * 仅 SSO 折叠管理员登录 / 强制改密页 / 身份认证页 / 用户管理认证来源+解绑 / 权限矩阵。
 *
 * 环境约定：前端 http://localhost:3000（nginx 代理 /api → gateway :8080）。
 * 前置：SSO 运行时配置已启用（enabled=true, mode=mixed）。
 * 全局配置（登录模式）临时改动在 finally 恢复。串行执行。
 */

test.describe.configure({mode: 'serial'});

const NEW_PW = 'E2eTest@456';

test.beforeAll(async () => {
    await seedUsers();
});

test.afterAll(async () => {
    cleanupUsers();
});

// ==================== 辅助 ====================

/** 通过 API 切换 SSO 登录模式（admin） */
async function setSsoMode(mode: 'mixed' | 'sso-only'): Promise<void> {
    const ctx = await pwRequest.newContext();
    try {
        const login = await ctx.post(`${API_BASE}/system/auth/login`, {
            data: {username: 'admin', password: 'admin123'},
        });
        const env = await login.json();
        const token = env.data.token;
        const headers = {Authorization: token};
        const cfg = (await (await ctx.get(`${API_BASE}/system/auth/sso/config`, {headers})).json()).data;
        cfg.mode = mode;
        await ctx.put(`${API_BASE}/system/auth/sso/config`, {headers: {...headers, 'Content-Type': 'application/json'}, data: JSON.stringify(cfg)});
    } finally {
        await ctx.dispose();
    }
}

/** 断言已进入系统首页（token 落库 + URL 归一） */
async function expectLoggedIn(page: Page): Promise<void> {
    await page.waitForURL('**/');
    await expect.poll(() => page.evaluate(() => localStorage.getItem('token'))).toBeTruthy();
}

/** 本地表单登录 */
async function localLogin(page: Page, username: string, password: string): Promise<void> {
    await page.getByPlaceholder('请输入用户名').fill(username);
    await page.getByPlaceholder('请输入密码').fill(password);
    await page.getByRole('button', {name: '登 录', exact: true}).click();
}

// ==================== 用例 ====================

test('SU-01 登录页混合模式：企业 SSO / AD 域登录入口 + 本地表单共存', async ({page}) => {
    await page.goto('/login');
    await expect(page.getByRole('button', {name: '企业 SSO 登录'})).toBeVisible();
    await expect(page.getByRole('button', {name: 'AD 域账号登录'})).toBeVisible();
    await expect(page.getByPlaceholder('请输入用户名')).toBeVisible();
    await expect(page.getByRole('button', {name: '登 录', exact: true})).toBeVisible();
});

test('SU-02 OIDC 全链路 UI：企业 SSO 登录 → IdP 授权 → 回调 → 进入系统', async ({page}) => {
    await page.goto('/login');
    await page.getByRole('button', {name: '企业 SSO 登录'}).click();
    // 整页跳 IdP 授权页（mock-idp 自动 302，默认 alice 已绑定用户）→ 回调 → 前端 useEffect 清 hash + navigate('/')
    // 中间态 /login#ssoToken=xxx 极短被 replaceState 清掉，直接等待最终进入系统
    await expectLoggedIn(page);
});

test('SU-03 仅 SSO 模式：本地表单隐藏 + 管理员本地登录折叠入口 + admin 保底', async ({page}) => {
    await setSsoMode('sso-only');
    try {
        await page.goto('/login');
        // 本地表单隐藏
        await expect(page.getByPlaceholder('请输入用户名')).not.toBeVisible();
        await expect(page.getByText('当前为仅企业身份登录模式')).toBeVisible();
        // 折叠入口 → 展开本地表单 → admin 登录成功（逃生通道）
        await page.getByRole('button', {name: '管理员本地登录'}).click();
        await expect(page.getByPlaceholder('请输入用户名')).toBeVisible();
        await localLogin(page, 'admin', 'admin123');
        await expectLoggedIn(page);
    } finally {
        await setSsoMode('mixed');
    }
});

test('SU-04 AD 域账号登录：弹窗登录成功', async ({page}) => {
    await page.goto('/login');
    await page.getByRole('button', {name: 'AD 域账号登录'}).click();
    const dialog = page.getByRole('dialog', {name: '企业域账号登录'});
    await expect(dialog).toBeVisible();
    await dialog.getByPlaceholder('请输入域账号').fill(LDAP_USER.username);
    await dialog.locator('input[name="ldap-password"]').fill(LDAP_USER.password);
    await dialog.getByRole('button', {name: '登录'}).click();
    await expectLoggedIn(page);
});

test('SU-05 密码过期强制改密：登录跳改密页 → 改密 → 进入系统', async ({page}) => {
    forcePasswordExpired(`${PREFIX}expire`);
    await page.goto('/login');
    await localLogin(page, `${PREFIX}expire`, PW);
    // 跳强制改密页
    await page.waitForURL('**/force-change-password');
    await expect(page.getByRole('heading', {name: '修改密码'})).toBeVisible();
    // 改密
    await page.getByPlaceholder('请输入当前密码').fill(PW);
    await page.getByPlaceholder('至少 6 位，需包含大小写字母和数字').fill(NEW_PW);
    await page.getByPlaceholder('请再次输入新密码').fill(NEW_PW);
    await page.getByRole('button', {name: '确认修改'}).click();
    await expectLoggedIn(page);
});

test('SU-06 身份认证页：表单加载 + 保存配置热生效', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/auth-config');
    await expect(page.getByRole('heading', {name: '身份认证'})).toBeVisible();
    await expect(page.getByText('SSO 总开关')).toBeVisible();
    await expect(page.getByText('登录模式')).toBeVisible();
    // 保存（不改动表单值，仅验证保存链路 + toast）
    await page.getByRole('button', {name: '保存配置'}).click();
    await expect(page.locator('.ant-message-notice-title').getByText('身份认证配置已保存并生效')).toBeVisible();
});

test('SU-07 用户管理：认证来源徽章 + 解绑企业身份', async ({page}) => {
    // 造数：e2e_s14_bind 置为 OIDC 绑定（模拟 SSO 登录绑定后状态）
    psqlSys(`UPDATE sys_user SET auth_source='OIDC', sso_subject='mock-sub-e2e-bind' WHERE username='${PREFIX}bind';`);
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/users');
    // 搜索目标用户
    await page.getByPlaceholder('搜索用户名或邮箱...').fill(`${PREFIX}bind`);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    // 认证来源徽章「企业 SSO」
    await expect(page.getByText('企业 SSO', {exact: true}).first()).toBeVisible();
    // 解绑操作（限定到 e2e_s14_bind 行，避开 lisi/zhangsan 等 LDAP 用户解绑按钮）
    await page.getByRole('row', {name: /e2e_s14_bind/}).getByLabel('解绑企业身份').click();
    await page.getByRole('button', {name: '确认解绑'}).click();
    await expect(page.locator('.ant-message-notice-title').getByText(/已解除.*企业身份绑定/)).toBeVisible();
    // 解绑后恢复本地账号（sso_subject 真正清空 —— 回归 unbindSso updateById null 坑）
    await expect(page.getByText('本地账号', {exact: true}).first()).toBeVisible();
    const row = queryUser(`${PREFIX}bind`);
    expect(row?.authSource).toBe('LOCAL');
    expect(row?.ssoSubject).toBe('');
});

test('SU-08 权限矩阵：engineer 无身份认证菜单且直接访问被拒', async ({page}) => {
    await gotoAs(page, `${PREFIX}engineer`, PW, '/');
    // 侧边栏无「身份认证」
    await expect(page.getByText('身份认证')).toHaveCount(0);
    // 直接访问身份认证页 → 后端 403（页面报错提示，不渲染配置表单）
    await page.goto('/system/auth-config');
    await expect(page.getByText('身份认证', {exact: true}).first()).not.toBeVisible();
});

test('SU-09 LDAP 用户同步 UI：同步目录用户成功提示', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/auth-config');
    await page.getByRole('button', {name: '同步目录用户'}).click();
    await expect(page.locator('.ant-message-notice-title').getByText(/同步完成：共 2 人，新增 0、更新 2/)).toBeVisible();
});
