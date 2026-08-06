import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS, TPL_PREFIX, QUALITY_PREFIX, QUALITY_DB, QUALITY_TABLE} from '../helpers/data';
import {psql, scalar} from '../helpers/db';
import {gotoAs} from '../helpers/e2e';

/** 元数据数据源 / 表 ID（与 seed.ts 一致，供批量应用选表） */
const QUALITY_DS_ID = '9000010000000000001';

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
/** 供模板库「批量应用」绑定的质量任务 */
let batchJobId = '';

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

/** 批量应用弹窗内嵌选表：点数据库列 → 点表列（多选 toggle） */
async function pickTableInBatchModal(page: Page) {
    const modal = page.getByRole('dialog', {name: '模板批量应用'});
    await modal.waitFor({state: 'visible', timeout: 10000});
    // 数据库列点击
    const dbCell = modal.getByText(QUALITY_DB, {exact: true});
    await dbCell.waitFor({state: 'visible', timeout: 10000});
    await dbCell.click();
    // 表列点击
    const tableCell = modal.getByText(QUALITY_TABLE, {exact: true});
    await tableCell.waitFor({state: 'visible', timeout: 10000});
    await tableCell.click();
}

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 规则模板库 E2E', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 建一个批量应用目标任务（模板库页无任务上下文，弹窗内选）
        const j = await admin.post('/governance/quality/jobs', {
            name: `${QUALITY_PREFIX}_tpl_batchjob`,
            description: 's6 templates batch apply target',
            enabled: 1,
        });
        batchJobId = String(j.data?.id ?? j.id);
    });

    test.afterAll(async () => {
        // 清理批量任务关联的规则 + 任务 + 本测试创建的自定义模板
        psql(`DELETE FROM quality_rule WHERE job_id = '${batchJobId}'`);
        psql(`DELETE FROM quality_job WHERE id = '${batchJobId}'`);
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

    test('页面加载：内置模板展示', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
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

    test('批量应用：从模板行进入 → 预选模板 + 弹窗内选目标任务 → 生成规则并绑定', async ({page}) => {
        const rulePrefix = `${QUALITY_PREFIX}_tpl_apply_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-templates');
        // 点「完整性检查」行的批量应用按钮
        const row = rowBy(page, '完整性检查');
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('批量应用').click();
        const modal = page.getByRole('dialog', {name: '模板批量应用'});
        await modal.waitFor({state: 'visible', timeout: 10000});
        // 模板库页无任务上下文 → 弹窗内出现「目标任务」下拉
        const jobSelect = modal.getByText(/目标任务/).locator('..').locator('select');
        await jobSelect.waitFor({state: 'visible', timeout: 10000});
        // 预选当前模板（完整性检查）→ 模板下拉已选中（非空），无需再手动选
        const templateSelect = modal.getByText(/选择模板/).locator('..').locator('select');
        await expect(templateSelect).not.toHaveValue('', {timeout: 10000});
        // 选目标任务
        await jobSelect.selectOption(batchJobId);
        // 选数据源（质量数据源，使表列可用）
        const dsSelect = modal.getByText(/^数据源/).locator('..').locator('select');
        await dsSelect.selectOption(QUALITY_DS_ID);
        // 内嵌选表：点数据库列 → 点表列（多选 toggle）
        await pickTableInBatchModal(page);
        // 生成规则
        await modal.getByRole('button', {name: '生成规则'}).click();
        await expect(modal).toHaveCount(0, {timeout: 10000});
        // DB 验证：规则已生成且绑定到该任务，名称含模板语义（完整性 → COMPLETENESS）
        const count = Number(scalar(
            `SELECT count(*) FROM quality_rule r
             JOIN quality_job_rule jr ON jr.rule_id = r.id
             WHERE jr.job_id = '${batchJobId}' AND r.type = 'COMPLETENESS'`,
        ));
        expect(count).toBeGreaterThanOrEqual(1);
        // 清理本用例生成的规则
        psql(`DELETE FROM quality_rule WHERE job_id = '${batchJobId}'`);
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
