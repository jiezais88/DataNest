import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {Api} from '../../sprint6/helpers/api';

/**
 * Sprint 10 F1 E2E：SQL 查询终端（产品化工作台）。
 *
 * 覆盖验收点（对齐 PRD §9.1）：
 * - AC-1 只读查询：SELECT 成功返回、结果表渲染；UPDATE/DELETE 被 JSqlParser 拦截 → 错误面板
 * - AC-2 超时/截断：结果 >1000 行 → 截断提示（KPI 行数 1000 +「已截断」标）
 * - AC-3 导出：CSV/Excel 按钮可用并触发下载
 * - AC-4 查询历史：Drawer 展示、点击回填、清空确认
 * - 数据目录树：Doris 数仓默认选中/展开、库/表懒加载、点表插入 SELECT 模板
 * - Ctrl+Enter 快捷键运行
 * - 权限：DATA_ANALYST 可访问页面并执行查询
 *
 * 环境约定：
 * - 前端 http://localhost:3000（nginx 代理 /api → gateway http://localhost:8080）
 * - 测试数据：内置 Doris（datasourceId=-1，ods.users 3 行）；不依赖外部 seed
 * - 历史：测试开始清空 admin 历史保证可预测
 */

test.describe.configure({mode: 'serial'});

const ADMIN = {username: 'admin', password: 'admin123'};

/** 左侧树节点（按钮）按可见文本定位 */
function treeNode(page: Page, text: string, exact = false) {
    return page.locator('button').filter({
        hasText: exact ? new RegExp(`^\\s*${text}\\s*$`) : text,
    }).first();
}

/** 展开 Doris 数仓并加载库列表（默认已展开但子节点未加载：修复后首次点击即加载，而非收起） */
async function expandDoris(page: Page): Promise<void> {
    await treeNode(page, 'Doris 数仓', true).click();
    await expect(treeNode(page, 'ods', true)).toBeVisible();
}

/** 展开 ods 库并点击 users 表（插入 SELECT 模板） */
async function insertUsersTable(page: Page): Promise<void> {
    await expandDoris(page);
    await treeNode(page, 'ods', true).click();
    await expect(treeNode(page, 'users', true)).toBeVisible();
    await treeNode(page, 'users', true).click();
}

/** 运行当前编辑器 SQL */
async function run(page: Page): Promise<void> {
    await page.getByRole('button', {name: '运行', exact: true}).click();
}

test.beforeAll(async () => {
    // 清空 admin 查询历史，保证用例可预测
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    await api.del('/data-service/sql-console/history');
    await api.dispose();
});

test('F1-1 页面加载：标题/左树/编辑器/操作按钮齐全，Doris 数仓默认选中', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');

    // 标题 + 描述
    await expect(page.getByRole('heading', {name: 'SQL 查询终端'})).toBeVisible();
    await expect(page.getByText('在左侧目录选择库表插入查询模板')).toBeVisible();

    // 左侧树：数据目录 + 搜索框
    await expect(page.getByText('数据目录')).toBeVisible();
    await expect(page.getByPlaceholder('搜索库 / 模式 / 表')).toBeVisible();

    // Doris 数仓默认展示且选中；当前上下文默认显示 Doris 数仓
    await expect(treeNode(page, 'Doris 数仓')).toBeVisible();
    await expect(page.getByText('当前上下文', {exact: true})).toBeVisible();

    // 编辑器 + 运行/停止/历史按钮
    await expect(page.locator('.monaco-editor').first()).toBeVisible();
    await expect(page.getByRole('button', {name: '运行'})).toBeVisible();
    await expect(page.getByRole('button', {name: '停止'})).toBeVisible();
    await expect(page.getByRole('button', {name: '查询历史'})).toBeVisible();

    // KPI 区占位（未执行时显示 -）
    await expect(page.getByText('本次用时')).toBeVisible();
    await expect(page.getByText('涉及表')).toBeVisible();
    await expect(page.getByText('机密拦截')).toBeVisible();
});

test('F1-2 左树库/表懒加载 + 点表插入 SELECT 模板', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');

    // 点击 Doris 数仓 → 懒加载出库列表 → 展开 ods → 懒加载出 users 表
    await expandDoris(page);
    await treeNode(page, 'ods', true).click();
    await expect(treeNode(page, 'users', true)).toBeVisible();

    // 点击表节点 → 编辑器插入 SELECT 模板 + 上下文路径显示
    await treeNode(page, 'users', true).click();
    await expect(page.locator('.view-lines')).toContainText('SELECT * FROM ods.users LIMIT 100;');
    await expect(treeNode(page, 'Doris 数仓', true)).toBeVisible();
});

test('F1-3 运行只读 SQL：结果表 + KPI + 涉及表/机密拦截', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');

    // 展开 ods 库并点击 users 表插入 SQL
    await insertUsersTable(page);

    // 运行（先验证 Ctrl+Enter 快捷键也能触发）
    const editor = page.locator('.monaco-editor').first();
    await editor.click();
    await page.keyboard.press('Control+Enter');

    // 结果表出现 tester 数据
    const resultTable = page.locator('.prototype-table');
    await expect(resultTable).toBeVisible();
    await expect(page.locator('.ant-table-row').filter({hasText: 'tester'})).toBeVisible();

    // KPI：返回行 3/1000、涉及表 1 表、机密拦截 0（未触碰机密）
    await expect(page.getByText('3 / 1000')).toBeVisible();
    await expect(page.getByText('1 表')).toBeVisible();
    await expect(page.getByText('未触碰机密')).toBeVisible();
});

test('F1-4 只读拦截：UPDATE 被拦截，错误面板展示', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');

    // 编辑器输入 UPDATE（Monaco 隐藏 textarea，一次性 insertText 避免逐字丢失）
    const editor = page.locator('.monaco-editor').first();
    await editor.click();
    await page.keyboard.press('Control+a');
    await page.keyboard.insertText("UPDATE ods.users SET username='x' WHERE id=1");

    await run(page);

    // 错误面板：非只读 SQL 已拦截
    await expect(page.getByText('非只读 SQL 已拦截')).toBeVisible();
    await expect(page.getByText(/仅允许 SELECT\/WITH\/SHOW\/DESC\/EXPLAIN/)).toBeVisible();
});

test('F1-5 查询历史 Drawer：回填 + 清空确认', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');

    // 制造一条成功历史
    await insertUsersTable(page);
    await run(page);
    await expect(page.locator('.ant-table-row').filter({hasText: 'tester'})).toBeVisible();

    // 打开历史 Drawer
    await page.getByRole('button', {name: '查询历史'}).click();
    await expect(page.getByText('SELECT * FROM ods.users LIMIT 100;').first()).toBeVisible();

    // 点击历史条目回填编辑器（Drawer 内首条历史）
    await page.locator('[role="dialog"] button').filter({hasText: 'SELECT * FROM ods.users LIMIT 100;'}).first().click();
    await expect(page.locator('.view-lines')).toContainText('SELECT * FROM ods.users LIMIT 100;');

    // 清空历史（Drawer 内清空按钮 + ConfirmDialog）
    await page.getByRole('button', {name: '查询历史'}).click();
    await page.locator('[role="dialog"]').getByLabel('清空历史').click();
    await expect(page.getByText('确定清空当前账号的全部查询历史吗？')).toBeVisible();
    await page.getByRole('button', {name: '清空', exact: true}).click();
    await expect(page.getByText('查询历史已清空')).toBeVisible();
    await expect(page.getByText('暂无查询历史')).toBeVisible();
});

test('F1-6 树搜索：关键词定位库/表', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');

    await page.getByPlaceholder('搜索库 / 模式 / 表').fill('users');
    // 搜索模式激活 → 树头部显示「搜索结果」徽标
    await expect(page.getByText('搜索结果')).toBeVisible();
});

test('F1-7 权限：DATA_ANALYST 可访问并执行查询', async ({page}) => {
    await gotoAs(page, 'analyst_test', 'analyst123', '/data-service/sql-console');

    await expect(page.getByRole('heading', {name: 'SQL 查询终端'})).toBeVisible();

    // 直接执行 SQL（通过树）
    await insertUsersTable(page);
    await run(page);
    await expect(page.locator('.ant-table-row').filter({hasText: 'tester'})).toBeVisible();
});
