import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    ANALYST,
    PREFIX,
    TARGET,
    cleanupS11,
    ensureUser,
    findAudit,
    resetSensitivity,
} from './helpers/seed';

/**
 * Sprint 11 F1 审计日志页 E2E（PRD §6.1.3/§6.1.4 + 验收 AL-2/AL-5~AL-10）。
 *
 * 覆盖验收点：
 * - AL-2  SQL 查询成功留痕（UI 全流程：SQL 终端执行 → 审计页查到，含行数/耗时）
 * - AL-5  按操作人筛选
 * - AL-6  按操作类型筛选
 * - AL-7  按时间范围筛选（今天）
 * - AL-8  详情抽屉：SQL 查询类展示完整 SQL 文本
 * - AL-9  分析师访问审计日志页 → 无权限（菜单不渲染 + 接口 403 + 页面无数据）
 * - AL-10 无编辑/删除入口、无删除接口（只增不改不删）
 * - 页面：筛选组合/重置/失败行浅红高亮/分页
 *
 * 造数（beforeAll 自播种，对齐触发类 spec 策略）：
 * - e2e_s11_page_u1 用户创建（CREATE/USER）
 * - SQL 查询成功（SELECT target_products，复位 PUBLIC 前置）
 * - SQL 查询失败（SELECT 不存在的表 → FAILURE 记录）
 * 审计记录保留（e2e_s11_ 前缀标识），测试产物清理。
 */

test.describe.configure({mode: 'serial'});

const PAGE_USER = `${PREFIX}page_u1`;

/** 表格行按文本过滤 */
function row(page: Page, text: string) {
    return page.locator('.ant-table-row').filter({hasText: text});
}

/** 审计页操作人输入框 */
function operatorInput(page: Page) {
    return page.getByPlaceholder('操作人...');
}

/** 点击「查询」触发筛选 */
async function search(page: Page) {
    await page.getByRole('button', {name: '查询', exact: true}).click();
}

// ==================== 播种 / 清理 ====================

test.beforeAll(async () => {
    resetSensitivity();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    // 权限隔离用例账号（幂等）
    await ensureUser(api, ANALYST);
    // ① 创建用户 → CREATE/USER
    await ensureUser(api, {username: PAGE_USER, password: 'Test123456', roles: ['DATA_ANALYST'], email: `${PAGE_USER}@test.io`});
    // ② SQL 查询成功（target_products PUBLIC）
    await api.post('/data-service/sql-console/execute', {
        datasourceId: Number(TARGET.datasourceId),
        sql: `SELECT * FROM ${TARGET.databaseName}.${TARGET.tableName} LIMIT 2`,
    });
    // ③ SQL 查询失败（表不存在 → FAILURE）
    await api.raw('POST', '/data-service/sql-console/execute', {
        datasourceId: Number(TARGET.datasourceId),
        sql: `SELECT * FROM ${TARGET.databaseName}.e2e_s11_not_exist_${Date.now()} LIMIT 1`,
    });
    // 等审计落库（异步 fail-open）
    await findAudit(api, {opType: 'CREATE', resourceType: 'USER', keyword: PAGE_USER});
    await findAudit(api, {opType: 'EXECUTE', resourceType: 'SQL_QUERY', keyword: 'target_products', result: 'SUCCESS'});
    await findAudit(api, {opType: 'EXECUTE', resourceType: 'SQL_QUERY', result: 'FAILURE'});
    await api.dispose();
});

test.afterAll(async () => {
    await cleanupS11();
});

// ==================== 页面基础 ====================

test('P1 页面加载：标题/描述/筛选工具栏/表格表头齐全', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await expect(page.getByRole('heading', {name: '审计日志'})).toBeVisible();
    await expect(page.getByText('追踪平台关键操作留痕')).toBeVisible();

    // 筛选工具栏 5 项 + 操作按钮
    await expect(operatorInput(page)).toBeVisible();
    await expect(page.getByLabel('按操作类型筛选')).toBeVisible();
    await expect(page.getByLabel('按资源类型筛选')).toBeVisible();
    await expect(page.getByText('关键词（资源/内容）...')).toBeVisible();
    await expect(page.getByRole('button', {name: '查询'})).toBeVisible();
    await expect(page.getByRole('button', {name: '重置'})).toBeVisible();

    // 表头
    for (const col of ['操作时间', '操作人', '操作类型', '资源', '内容摘要', '结果', 'IP']) {
        await expect(page.getByText(col, {exact: true}).first()).toBeVisible();
    }
});

test('P2 数据呈现：默认近 7 天展示审计记录（含 e2e_s11_ 造数）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    // 造数记录（操作人 e2e_s11_page_u1）应出现在列表
    await expect(row(page, PAGE_USER).first()).toBeVisible();
});

// ==================== 筛选（AL-5 / AL-6 / AL-7） ====================

test('P3 AL-5 按操作人筛选：结果只含该操作人', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await operatorInput(page).fill(PREFIX);
    await search(page);

    // 造数记录出现在结果中
    await expect(row(page, PAGE_USER).first()).toBeVisible();
    // 抽查：第一行操作人列 = e2e_s11_page_u1（筛选后首条为最新造数记录）
    await expect(page.locator('.ant-table-row').first()).toContainText(PREFIX);
});

test('P4 AL-6 按操作类型筛选：SQL 查询', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await page.getByLabel('按操作类型筛选').selectOption({label: 'SQL 查询'});
    await search(page);

    // 结果出现 target_products 成功记录；首行操作类型 = 执行
    await expect(row(page, 'target_products').first()).toBeVisible();
    await expect(page.locator('.ant-table-row').first()).toContainText('执行');
    await expect(page.locator('.ant-table-row').first()).toContainText('SQL 查询');
});

test('P5 按资源类型筛选：用户', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await page.getByLabel('按资源类型筛选').selectOption({label: '用户'});
    await search(page);

    // 首行资源列 = 用户: e2e_s11_page_u1
    await expect(row(page, PAGE_USER).first()).toBeVisible();
    await expect(page.locator('.ant-table-row').first()).toContainText('用户:');
});

test('P6 AL-7 按时间范围筛选：今天', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 选择今天 00:00 ~ 23:59（时间范围筛选，默认已近 7 天；改成今日验证收窄）
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    // DsRangePicker 由两个 datetime-local input 组成（from/to），按值填充
    const inputs = page.locator('input[type="datetime-local"]');
    await inputs.nth(0).fill(`${today}T00:00:00`);
    await inputs.nth(1).fill(`${today}T23:59:59`);
    await search(page);

    // 今日造数记录仍可见
    await expect(row(page, PAGE_USER).first()).toBeVisible();
});

test('P7 关键词筛选：匹配资源/内容（target_products）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await page.getByPlaceholder('关键词（资源/内容）...').fill('target_products');
    await search(page);

    // 结果行内容/资源含 target_products
    await expect(row(page, 'target_products').first()).toBeVisible();
    await expect(page.locator('.ant-table-row').first()).toContainText('target_products');
});

test('P8 重置：清空筛选条件恢复默认查询', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await operatorInput(page).fill('不存在的操作人xyz');
    await search(page);
    // 空结果态
    await expect(page.getByText('暂无审计记录')).toBeVisible();

    await page.getByRole('button', {name: '重置'}).click();
    // 恢复默认（近 7 天）→ 造数记录重新出现
    await expect(row(page, PAGE_USER).first()).toBeVisible();
});

// ==================== 详情 / 高亮 / 分页 ====================

test('P9 AL-8 详情抽屉：SQL 查询类展示完整 SQL 文本', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 关键词过滤到 target_products 的 SQL 记录
    await page.getByPlaceholder('关键词（资源/内容）...').fill('target_products');
    await search(page);

    // 点击 SQL 记录行 → 详情抽屉
    await row(page, 'target_products').first().click();
    const drawer = page.locator('[role="dialog"]');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('审计详情')).toBeVisible();

    // SQL 查询类：展示完整 SQL 文本（label = 执行 SQL）
    await expect(drawer.getByText('执行 SQL')).toBeVisible();
    await expect(drawer.getByText(/SELECT \* FROM datanest\.target_products/)).toBeVisible();
    // 行数/耗时内容也在
    await expect(drawer.getByText(/行数:/)).toBeVisible();
});

test('P10 失败行高亮：FAILURE 记录行浅红底色', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 失败造数（SELECT 不存在的表）：操作人 admin，结果列=失败
    await operatorInput(page).fill('admin');
    await page.getByLabel('按操作类型筛选').selectOption({label: 'SQL 查询'});
    await search(page);

    // 失败行带浅红背景 class（对齐页面实现 bg-ds-danger-light）
    const failRow = page.locator('.ant-table-row.bg-ds-danger-light').first();
    await expect(failRow).toBeVisible();
    await expect(failRow.getByText('失败')).toBeVisible();
});

test('P11 分页：总数展示与翻页可用', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 总条数展示（antd Pagination total）
    await expect(page.locator('.ant-pagination-total-text').first()).toBeVisible();
    // 翻到最后一页（若多页），再翻回第一页——保证分页控件可用
    const next = page.locator('.ant-pagination-next');
    if (await next.isEnabled()) {
        await next.click();
        await expect(page.locator('.ant-pagination-item-active')).toBeVisible();
    }
    await page.locator('.ant-pagination-item-1').first().click();
    await expect(page.locator('.ant-pagination-item-active')).toContainText('1');
});

// ==================== 权限（AL-9）与只增不改（AL-10） ====================

test('P12 AL-9 分析师无权限：侧边栏无审计菜单 + 直访页面无数据', async ({page}) => {
    await gotoAs(page, ANALYST.username, ANALYST.password, '/system/audit-logs');

    // ① 侧边栏不渲染「审计日志」菜单（前端权限核心）
    await expect(page.locator('.ant-menu').getByText('审计日志')).toHaveCount(0);
    // ② 直访页面：接口 403 → 列表无数据（不出现审计内容）
    await expect(page.getByRole('heading', {name: '审计日志'})).not.toBeVisible();
    await expect(page.getByText(PREFIX)).toHaveCount(0);
});

test('P13 AL-10 只增不改不删：页面无编辑/删除入口，后端无删除接口', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 页面无「编辑」「删除」按钮
    await expect(page.getByRole('button', {name: '编辑'})).toHaveCount(0);
    await expect(page.getByRole('button', {name: '删除'})).toHaveCount(0);

    // 后端无审计删除/修改接口（API 辅助诊断）
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        const del = await api.raw('DELETE', '/system/audit-logs/1');
        expect(del.code).not.toBe(200);
        const put = await api.raw('PUT', '/system/audit-logs/1', {result: 'SUCCESS'});
        expect(put.code).not.toBe(200);
    } finally {
        await api.dispose();
    }
});

// ==================== 端到端业务流程（AL-2 全流程） ====================

test('P14 端到端：SQL 终端执行查询 → 审计页出现对应留痕', async ({page}) => {
    // ① 用户（admin）在 SQL 终端执行一次查询（真实 UI 操作）
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/sql-console');
    await expect(page.getByRole('heading', {name: 'SQL 查询终端'})).toBeVisible();

    // 展开 Doris → ods → users 表插入模板 → 运行
    const treeNode = (text: string, exact = false) =>
        page.locator('button').filter({hasText: exact ? new RegExp(`^\\s*${text}\\s*$`) : text}).first();
    await expect(treeNode('ods', true)).toBeVisible();
    await treeNode('ods', true).click();
    await expect(treeNode('users', true)).toBeVisible();
    await treeNode('users', true).click();
    await expect(page.locator('.view-lines')).toContainText('SELECT * FROM ods.users LIMIT 100;');
    await page.getByRole('button', {name: '运行', exact: true}).click();
    // 查询成功：结果表出现 tester 数据
    await expect(page.locator('.ant-table-row').filter({hasText: 'tester'}).first()).toBeVisible();

    // ② 回到审计页，按关键词 ods.users 筛选 → 出现本次查询留痕（AL-2）
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    await page.getByPlaceholder('关键词（资源/内容）...').fill('ods.users');
    await search(page);

    const auditRow = row(page, 'ods.users').first();
    await expect(auditRow).toBeVisible();
    await expect(auditRow).toContainText('执行');
    await expect(auditRow).toContainText('成功');
    await expect(auditRow).toContainText('行数:');
    await expect(auditRow).toContainText('耗时:');
});
