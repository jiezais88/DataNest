import {type Page, request as pwRequest} from '@playwright/test';
import {API_BASE} from './api';

/**
 * E2E 登录辅助：通过 API 获取 token + userInfo，注入 localStorage 后访问页面。
 * 避免 UI 登录的脆弱性与重复耗时。
 */
export async function loginAs(page: Page, username: string, password: string): Promise<void> {
    const ctx = await pwRequest.newContext();
    const res = await ctx.fetch(`${API_BASE}/system/auth/login`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        data: JSON.stringify({username, password}),
    });
    const env = await res.json();
    if (env.code !== 200) {
        throw new Error(`E2E 登录失败: ${username} ${JSON.stringify(env)}`);
    }
    const {token, userInfo} = env.data;
    await page.addInitScript(
        ([t, u]) => {
            localStorage.setItem('token', t);
            localStorage.setItem('datanest_user_info', JSON.stringify(u));
        },
        [token, userInfo],
    );
    await ctx.dispose();
}

export async function gotoAs(page: Page, username: string, password: string, path: string): Promise<void> {
    await loginAs(page, username, password);
    await page.goto(path);
}
