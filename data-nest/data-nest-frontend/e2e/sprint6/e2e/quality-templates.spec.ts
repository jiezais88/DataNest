import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS, TPL_PREFIX} from '../helpers/data';
import {psql} from '../helpers/db';
import {gotoAs} from '../helpers/e2e';

/**
 * Sprint 6 规则模板库 E2E 测试（页面：/governance/quality-templates）
 * 覆盖：
 * - 菜单可见性（数据治理分组，治理员可见）
 * - 页面加载：统计卡片 + 表格展示内置模板
 * - 筛选：类型 / 来源 / 状态 / 关键字
 * - 新增自定义模板（Drawer）
 * - 详情查看（只读）
 * - 编辑模板
 * - 启停模板
 * - 删除自定义模板（ConfirmDialog）
 * - 内置模板不可删除
 * - 权限：工程师可查看但不可编辑
 */

let admin: Api;

/** 定位当前行：按「模板名称」列精确匹配表格行（避免与类型列文字混淆） */
function rowBy(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({
        has: page.locator('.ant-table-cell:first-child').getByText(name, {exact: true}),
    });
}

/** 新增模板 Drawer：填写并保存（name 必填；type 选 COMPLETENESS；resultMetric 与 sqlTemplate 必填） */
async function fillCreateDrawer(page: Page, name: string, opts: { type?: string } = {}) {
    const drawer = page.getByRole('dialog', {name: '新增自定义模板'});
    await drawer.waitFor({state: 'visible', timeout: 10000});
    // 模板名称
    await drawer.locator('input').nth(0).fill(name);
    // 模板类型按钮
    await drawer.getByText(opts.type ?? '完整性检查', {exact: true}).click();
    // 结果指标名（第 2 个 input）
    await drawer.locator('input').nth(1).fill('null_rate');
    // 校验 SQL 模板（第 1 个 textarea）
    await drawer.locator('textarea').nth(0).fill(
        'SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}',
    );
    // 启用模板 checkbox
    await drawer.locator('input[type="checkbox"]').first().check();
    return drawer;
}

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 规则模板库 E2E', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
    });

    test.afterAll(async () => {
        // 清理本测试创建的自定义模板（前缀 e2e_s6）
        psql(`DELETE
              FROM quality_rule_template
              WHERE name LIKE '${TPL_PREFIX}%'`);
        await admin.dispose();
    });

    test('菜单：治理员可见「规则模板库」并可进入', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/');
        await page.getByText('数据治理', {exact: true}).click();
        await page.getByText('规则模板库', {exact: true}).click();
        await expect(page.getByRole('heading', {name: '规则模板库'})).toBeVisible({timeout: 15000});
    });

    test('页面加载：统计卡片与内置模板展示', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        // 统计卡片
        await expect(page.getByText('模板总数', {exact: true})).toBeVisible();
        await expect(page.getByText('内置模板', {exact: true})).toBeVisible();
        await expect(page.getByText('自定义模板', {exact: true})).toBeVisible();
        // 内置四类模板在表格中
        await expect(rowBy(page, '完整性检查')).toBeVisible({timeout: 15000});
        await expect(rowBy(page, '唯一性检查')).toBeVisible();
        await expect(rowBy(page, '值域范围检查')).toBeVisible();
        await expect(rowBy(page, '自定义 SQL')).toBeVisible();
        // 内置模板来源徽章为「内置」
        await expect(rowBy(page, '完整性检查').getByText('内置', {exact: true})).toBeVisible();
    });

    test('筛选：按类型过滤只显示该类', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        const typeSelect = page.locator('select').nth(0); // 类型下拉
        await typeSelect.selectOption('COMPLETENESS');
        await page.getByRole('button', {name: '查询'}).click();
        // 唯一性检查被过滤掉（非 COMPLETENESS）
        await expect(rowBy(page, '唯一性检查')).toHaveCount(0, {timeout: 15000});
        // 完整性检查仍在
        await expect(rowBy(page, '完整性检查')).toBeVisible();
        // 重置
        await page.getByRole('button', {name: '重置'}).click();
    });

    test('筛选：来源（自定义）与状态（停用）', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        // 来源=自定义：仅显示自定义模板（e2e_s6_*），内置不显示
        const sourceSelect = page.locator('select').nth(1);
        await sourceSelect.selectOption('0');
        await page.getByRole('button', {name: '查询'}).click();
        await expect(rowBy(page, `${TPL_PREFIX}_完整性`)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, '完整性检查')).toHaveCount(0);
        await page.getByRole('button', {name: '重置'}).click();

        // 状态=停用：仅显示停用模板（e2e_s6_停用模板）
        const statusSelect = page.locator('select').nth(2);
        await statusSelect.selectOption('0');
        await page.getByRole('button', {name: '查询'}).click();
        await expect(rowBy(page, `${TPL_PREFIX}_停用模板`)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, '完整性检查')).toHaveCount(0);
    });

    test('筛选：关键字搜索模板名称', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        const search = page.getByPlaceholder('搜索模板名称...');
        await search.fill('完整性');
        await page.getByRole('button', {name: '查询'}).click();
        // 唯一性检查被过滤，完整性检查保留
        await expect(rowBy(page, '唯一性检查')).toHaveCount(0, {timeout: 15000});
        await expect(rowBy(page, '完整性检查')).toBeVisible();
        await page.getByRole('button', {name: '重置'}).click();
    });

    test('新增自定义模板：Drawer 填写并保存', async ({page}) => {
        const name = `${TPL_PREFIX}_新增_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        await page.getByRole('button', {name: '新增自定义模板'}).first().click();
        const drawer = await fillCreateDrawer(page, name);
        await drawer.getByRole('button', {name: '保存'}).click();
        // 保存后 Drawer 关闭，列表出现新模板
        await expect(drawer).toHaveCount(0, {timeout: 10000});
        await expect(rowBy(page, name)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, name).getByText('自定义', {exact: true})).toBeVisible();
        await expect(rowBy(page, name).getByText('启用', {exact: true})).toBeVisible();
        // 创建人/修改人均为当前用户（出现多次，断言至少一处）
        await expect(rowBy(page, name).getByText('s6_govadmin').first()).toBeVisible();
    });

    test('详情查看：只读展示模板信息', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        await expect(rowBy(page, '完整性检查')).toBeVisible({timeout: 15000});
        await rowBy(page, '完整性检查').getByLabel('详情').click();
        const drawer = page.getByRole('dialog', {name: '模板详情'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        // 内置徽章
        await expect(drawer.getByText('内置', {exact: true})).toBeVisible();
        // 只读：无保存按钮，输入框禁用
        await expect(drawer.getByRole('button', {name: '保存'})).toHaveCount(0);
        await expect(drawer.locator('input').first()).toBeDisabled();
    });

    test('编辑模板：修改名称并保存', async ({page}) => {
        const oldName = `${TPL_PREFIX}_完整性`;
        const newName = `${TPL_PREFIX}_完整性_改_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        const row = rowBy(page, oldName);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('编辑').click();
        const drawer = page.getByRole('dialog', {name: '编辑模板'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        await drawer.locator('input').nth(0).fill(newName);
        await drawer.getByRole('button', {name: '保存'}).click();
        await expect(rowBy(page, newName)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, oldName)).toHaveCount(0);
        psql(`DELETE FROM quality_rule_template WHERE name = '${newName}'`);
    });

    test('启停模板：停用后状态变为停用，可恢复', async ({page}) => {
        const name = `${TPL_PREFIX}_唯一性`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('启用', {exact: true})).toBeVisible();
        // 停用
        await row.getByLabel('停用').click();
        await expect(row.getByText('停用', {exact: true})).toBeVisible({timeout: 10000});
        // 恢复启用（保持 seed 状态，避免影响其它用例）
        await row.getByLabel('启用').click();
        await expect(row.getByText('启用', {exact: true})).toBeVisible({timeout: 10000});
    });

    test('删除自定义模板：ConfirmDialog 确认删除', async ({page}) => {
        const name = `${TPL_PREFIX}_待删_${Date.now()}`;
        // 用 admin API 建一条，确保删除对象存在
        await admin.post('/governance/quality/templates', {
            name, type: 'CUSTOM_SQL', description: 's6 to delete',
            sqlTemplate: 'SELECT 1 AS v', resultMetric: 'custom_value', enabled: 1,
        });
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('删除').click();
        const dialog = page.getByRole('dialog', {name: '删除确认'});
        await dialog.waitFor({state: 'visible', timeout: 10000});
        await dialog.getByRole('button', {name: /确认删除/}).click();
        await expect(row).toHaveCount(0, {timeout: 10000});
    });

    test('内置模板不可删除：无删除按钮', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        const row = rowBy(page, '完整性检查');
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByLabel('删除')).toHaveCount(0);
    });

    test('权限：工程师可查看但不可编辑', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/governance/quality-templates');
        // 可查看：标题与内置模板
        await expect(page.getByRole('heading', {name: '规则模板库'})).toBeVisible();
        await expect(rowBy(page, '完整性检查')).toBeVisible({timeout: 15000});
        // 不可编辑：无新增按钮
        await expect(page.getByRole('button', {name: '新增自定义模板'})).toHaveCount(0);
        // 行内无启停/编辑/删除图标按钮（只读）
        const row = rowBy(page, '完整性检查');
        await expect(row.getByLabel('停用')).toHaveCount(0);
        await expect(row.getByLabel('编辑')).toHaveCount(0);
        await expect(row.getByLabel('删除')).toHaveCount(0);
    });
});
