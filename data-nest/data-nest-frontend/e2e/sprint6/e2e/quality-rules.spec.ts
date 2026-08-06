import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS, QUALITY_PREFIX, QUALITY_DB, QUALITY_TABLE} from '../helpers/data';
import {psql} from '../helpers/db';
import {gotoAs} from '../helpers/e2e';

/**
 * Sprint 6 质量规则 E2E 测试（页面：/governance/data-quality → 质量规则 Tab）
 * 覆盖：
 * - Tab 切换 + 选任务后展示该任务规则
 * - 新增规则（COMPLETENESS 整表 / UNIQUENESS 选字段 / CUSTOM_SQL）
 * - 预览 SQL（CUSTOM_SQL 规则展开执行 SQL）
 * - 详情查看（只读）
 * - 编辑规则
 * - 启停规则
 * - 删除规则（ConfirmDialog）
 * - 模板批量应用（选模板 + 选表 → 生成规则）
 * - 权限：工程师可查看但不可编辑
 *
 * 测试数据前缀 e2e_s6_q，元数据由 seedQualityMetadata 提供（e2e_s6_qdb.e2e_s6_orders + id/order_no/amount 字段）。
 */

let admin: Api;

/** 测试元数据数据源 ID / 表 ID（与 seed.ts 一致） */
const QUALITY_DS_ID = '9000010000000000001';
const QUALITY_TABLE_ID = '9000010000000000002';

/** 定位当前行：按「规则名称」列精确匹配表格行 */
function rowBy(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({
        has: page.locator('.ant-table-cell:first-child').getByText(name, {exact: true}),
    });
}

/** 批量应用弹窗内嵌选表：选数据源 → 点数据库列 → 点表列（多选 toggle） */
async function pickTableInBatchModal(page: Page, tableName: string) {
    const modal = page.getByRole('dialog', {name: '模板批量应用'});
    await modal.waitFor({state: 'visible', timeout: 10000});
    // 数据源下拉（label「数据源 *」）
    const dsSelect = modal.getByText(/^数据源/).locator('..').locator('select');
    await dsSelect.waitFor({state: 'visible', timeout: 10000});
    await dsSelect.selectOption(QUALITY_DS_ID);
    // 数据库列点击
    const dbCell = modal.getByText(QUALITY_DB, {exact: true});
    await dbCell.waitFor({state: 'visible', timeout: 10000});
    await dbCell.click();
    // 表列点击
    const tableCell = modal.getByText(tableName, {exact: true});
    await tableCell.waitFor({state: 'visible', timeout: 10000});
    await tableCell.click();
}

/** 进入质量规则独立页 /governance/quality-rules 并选中指定任务 */
async function goRulesTab(page: Page, jobId: string) {
    const select = page.getByLabel('按所属任务筛选');
    await select.waitFor({state: 'visible', timeout: 10000});
    await select.selectOption(jobId);
}

/** 新增规则 Drawer：填写并保存（不同类型路径不同） */
async function fillRuleCreateDrawer(
    page: Page,
    name: string,
    opts: { type?: 'COMPLETENESS' | 'UNIQUENESS' | 'RANGE' | 'CUSTOM_SQL'; columnName?: string; sql?: string; thresholds?: [string, string] } = {},
) {
    const drawer = page.getByRole('dialog', {name: '新增质量规则'});
    await drawer.waitFor({state: 'visible', timeout: 10000});
    const type = opts.type ?? 'COMPLETENESS';
    // 规则名称
    await drawer.getByPlaceholder('例如：订单表订单号唯一性检查').fill(name);
    // 规则类型（label 含必填星号，用正则匹配）
    if (type !== 'COMPLETENESS') {
        const typeSelect = drawer
            .getByText(/规则类型/)
            .locator('..')
            .locator('select');
        await typeSelect.waitFor({state: 'visible', timeout: 10000});
        await typeSelect.selectOption(type);
    }
    // 规则模板（模板类规则必选；CUSTOM_SQL 不显示该下拉）
    if (type !== 'CUSTOM_SQL') {
        const templateSelect = drawer
            .getByText(/规则模板/)
            .locator('..')
            .locator('select');
        await templateSelect.waitFor({state: 'visible', timeout: 10000});
        const tplRegex = type === 'UNIQUENESS' ? /唯一性检查/ : type === 'RANGE' ? /值域范围检查/ : /完整性检查/;
        const tplOption = templateSelect.locator('option').filter({hasText: tplRegex}).first();
        await tplOption.waitFor({state: 'attached', timeout: 10000});
        const tplValue = (await tplOption.getAttribute('value'))!;
        await templateSelect.selectOption(tplValue);
    }
    // 目标表（内嵌级联：数据源 → 数据库 → 目标表；测试数据源为无 Schema 的 MYSQL）
    await drawer.getByText(/^数据源/).locator('..').locator('select').selectOption(QUALITY_DS_ID);
    const dbSelect = drawer.getByText(/^数据库/).locator('..').locator('select');
    await dbSelect.waitFor({state: 'visible', timeout: 10000});
    await dbSelect.selectOption({label: QUALITY_DB});
    const tableSelect = drawer.getByText(/^目标表/).locator('..').locator('select');
    await tableSelect.waitFor({state: 'visible', timeout: 10000});
    await tableSelect.selectOption({label: QUALITY_TABLE});
    // 检查字段（UNIQUENESS / RANGE / 按字段 COMPLETENESS）
    if (opts.columnName) {
        // COMPLETENESS 的 label 为「检查方式 *」，需先点「按字段检查」按钮再选字段；UNIQUENESS/RANGE 的 label 为「检查字段 *」
        const fieldLabel = type === 'COMPLETENESS' ? /检查方式/ : /检查字段/;
        if (type === 'COMPLETENESS') {
            await drawer.getByRole('button', {name: '按字段检查'}).click();
        }
        const fieldSelect = drawer.getByText(fieldLabel).locator('..').locator('select');
        await fieldSelect.waitFor({state: 'visible', timeout: 10000});
        await fieldSelect.selectOption({label: opts.columnName});
    }
    // 自定义 SQL
    if (opts.sql) {
        await drawer.getByPlaceholder('返回单个统计值的自定义校验 SQL').fill(opts.sql);
    }
    // 阈值
    const [w, s] = opts.thresholds ?? ['0.5', '0.8'];
    if (type === 'RANGE') {
        await drawer.getByPlaceholder('最小值').fill(w);
        await drawer.getByPlaceholder('最大值').fill(s);
    } else if (type !== 'CUSTOM_SQL') {
        await drawer.getByPlaceholder('结果 ≥ 此值 → 警告').fill(w);
        await drawer.getByPlaceholder('结果 ≥ 此值 → 严重').fill(s);
    }
    return drawer;
}

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 质量规则 E2E', () => {
    // 供规则 CRUD 的任务
    let jobId = '';
    // 供批量应用的任务
    let batchJobId = '';
    // 预建规则 id（供详情/编辑/启停/删除）
    const preName = `${QUALITY_PREFIX}_pre_rule`;

    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 清理历史
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%'`);
        // 创建规则测试任务（绑定 e2e_s6 数据源，选表默认数据源即此）
        const job = await admin.post('/governance/quality/jobs', {
            name: `${QUALITY_PREFIX}_rulejob`, description: 's6 rules',
            datasourceId: QUALITY_DS_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 0,
            alertLevel: 'SEVERE_WARNING',
        });
        jobId = String(job.id);
        // 批量应用测试任务
        const bj = await admin.post('/governance/quality/jobs', {
            name: `${QUALITY_PREFIX}_batchjob`, description: 's6 batch',
            datasourceId: QUALITY_DS_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 0,
            alertLevel: 'SEVERE_WARNING',
        });
        batchJobId = String(bj.id);
        // 预建一条规则（COMPLETENESS 按字段 + 模板），供详情/编辑/启停/删除
        await admin.post('/governance/quality/rules', {
            jobId, name: preName, type: 'COMPLETENESS', templateId: 1, tableId: QUALITY_TABLE_ID,
            checkField: 1, columnName: 'id', warningThreshold: 0.5, severeThreshold: 0.8, resultMetric: 'null_rate',
            weight: 1, enabled: 1,
        });
    });

    test.afterAll(async () => {
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%'`);
        await admin.dispose();
    });

    test('质量规则 Tab：选任务后展示该任务规则', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        // 预建规则出现
        await expect(rowBy(page, preName)).toBeVisible({timeout: 15000});
        // 完整性类型徽章
        await expect(rowBy(page, preName).getByText('完整性', {exact: true})).toBeVisible();
        // 对象表
        await expect(rowBy(page, preName).getByText(QUALITY_TABLE, {exact: true})).toBeVisible();
        // 检查字段（COMPLETENESS 按字段 id）
        await expect(rowBy(page, preName).getByText('id', {exact: true})).toBeVisible();
    });

    test('新增规则：COMPLETENESS 按字段检查', async ({page}) => {
        const name = `${QUALITY_PREFIX}_rule_comp_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        await page.getByRole('button', {name: '新增规则'}).first().click();
        const drawer = await fillRuleCreateDrawer(page, name, {columnName: 'id', thresholds: ['0.5', '0.8']});
        await drawer.getByRole('button', {name: '保存'}).click();
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('完整性', {exact: true})).toBeVisible();
        await expect(row.getByText('id', {exact: true})).toBeVisible();
    });

    test('新增规则：UNIQUENESS 选字段', async ({page}) => {
        const name = `${QUALITY_PREFIX}_rule_uniq_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        await page.getByRole('button', {name: '新增规则'}).first().click();
        const drawer = await fillRuleCreateDrawer(page, name, {type: 'UNIQUENESS', columnName: 'id', thresholds: ['0.5', '0.8']});
        await drawer.getByRole('button', {name: '保存'}).click();
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('唯一性', {exact: true})).toBeVisible();
        // 检查字段列展示 id
        await expect(row.getByText('id', {exact: true})).toBeVisible();
    });

    test('新增规则：CUSTOM_SQL 自定义校验', async ({page}) => {
        const name = `${QUALITY_PREFIX}_rule_custom_${Date.now()}`;
        const sql = `SELECT COUNT(*) AS total FROM ${QUALITY_TABLE}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        await page.getByRole('button', {name: '新增规则'}).first().click();
        const drawer = await fillRuleCreateDrawer(page, name, {type: 'CUSTOM_SQL', sql});
        await drawer.getByRole('button', {name: '保存'}).click();
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('自定义 SQL', {exact: true})).toBeVisible();
    });

    test('预览 SQL：CUSTOM_SQL 规则展开执行 SQL', async ({page}) => {
        const sql = `SELECT COUNT(*) AS total FROM ${QUALITY_TABLE}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        // 复用上面创建的 CUSTOM_SQL 规则（若无则断言至少存在一条 CUSTOM_SQL）
        const customRow = page.locator('.ant-table-row').filter({
            has: page.locator('.ant-table-cell').getByText('自定义 SQL', {exact: true}),
        }).first();
        await expect(customRow).toBeVisible({timeout: 15000});
        await customRow.getByLabel('预览 SQL').click();
        const modal = page.getByRole('dialog', {name: '规则执行 SQL 预览'});
        await modal.waitFor({state: 'visible', timeout: 10000});
        await expect(modal.getByText(sql, {exact: true})).toBeVisible();
        // footer 关闭按钮（右上角 X 与 footer「关闭」同名，取最后一个）
        await modal.getByRole('button', {name: '关闭'}).last().click();
    });

    test('详情查看：只读展示规则信息', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        const row = rowBy(page, preName);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('详情').click();
        const drawer = page.getByRole('dialog', {name: '质量规则详情'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        await expect(drawer.getByRole('button', {name: '保存'})).toHaveCount(0);
        await expect(drawer.getByPlaceholder('例如：订单表订单号唯一性检查')).toBeDisabled();
    });

    test('编辑规则：修改名称并保存', async ({page}) => {
        const newName = `${QUALITY_PREFIX}_pre_rule_改_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        const row = rowBy(page, preName);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('编辑').click();
        const drawer = page.getByRole('dialog', {name: '编辑质量规则'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        await drawer.getByPlaceholder('例如：订单表订单号唯一性检查').fill(newName);
        await drawer.getByRole('button', {name: '保存'}).click();
        await expect(rowBy(page, newName)).toBeVisible({timeout: 15000});
        // 改回，避免影响后续用例
        await rowBy(page, newName).getByLabel('编辑').click();
        const drawer2 = page.getByRole('dialog', {name: '编辑质量规则'});
        await drawer2.waitFor({state: 'visible', timeout: 10000});
        await drawer2.getByPlaceholder('例如：订单表订单号唯一性检查').fill(preName);
        await drawer2.getByRole('button', {name: '保存'}).click();
        await expect(rowBy(page, preName)).toBeVisible({timeout: 15000});
    });

    test('启停规则：停用后状态变停用，可恢复', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        const row = rowBy(page, preName);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('启用', {exact: true})).toBeVisible();
        await row.getByLabel('停用').click();
        await expect(row.getByText('停用', {exact: true})).toBeVisible({timeout: 10000});
        await row.getByLabel('启用').click();
        await expect(row.getByText('启用', {exact: true})).toBeVisible({timeout: 10000});
    });

    test('删除规则：ConfirmDialog 确认删除', async ({page}) => {
        const name = `${QUALITY_PREFIX}_rule_del_${Date.now()}`;
        await admin.post('/governance/quality/rules', {
            jobId, name, type: 'COMPLETENESS', templateId: 1, tableId: QUALITY_TABLE_ID,
            checkField: 1, columnName: 'id', warningThreshold: 0.5, severeThreshold: 0.8, weight: 1, enabled: 1,
        });
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('删除').click();
        const dialog = page.getByRole('dialog', {name: '删除确认'});
        await dialog.waitFor({state: 'visible', timeout: 10000});
        await dialog.getByRole('button', {name: /确认删除/}).click();
        await expect(row).toHaveCount(0, {timeout: 10000});
    });

    test('模板批量应用：选内置完整性模板 + 选表 → 生成规则', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-rules');
        await goRulesTab(page, batchJobId);
        // batchJob 初始无规则
        await expect(page.locator('.ant-table-row')).toHaveCount(0, {timeout: 15000});
        await page.getByRole('button', {name: '模板批量应用'}).click();
        const modal = page.getByRole('dialog', {name: '模板批量应用'});
        await modal.waitFor({state: 'visible', timeout: 10000});
        // 选模板（内置「完整性检查」；label 含必填星号，用正则匹配）
        const templateSelect = modal
            .getByText(/选择模板/)
            .locator('..')
            .locator('select');
        await templateSelect.waitFor({state: 'visible', timeout: 10000});
        await templateSelect.selectOption({label: '完整性检查'});
        // 内嵌选表（选数据源 → 点数据库 → 点表）
        await pickTableInBatchModal(page, QUALITY_TABLE);
        // 生成规则
        await modal.getByRole('button', {name: '生成规则'}).click();
        await expect(modal).toHaveCount(0, {timeout: 10000});
        // 列表出现批量生成的规则（行数 > 0）
        await expect(page.locator('.ant-table-row').first()).toBeVisible({timeout: 15000});
        await expect(page.locator('.ant-table-row')).toHaveCount(1);
    });

    test('权限：工程师可查看但不可编辑质量规则', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/governance/quality-rules');
        await goRulesTab(page, jobId);
        const row = rowBy(page, preName);
        await expect(row).toBeVisible({timeout: 15000});
        // 无新增规则 / 模板批量应用按钮
        await expect(page.getByRole('button', {name: '新增规则'})).toHaveCount(0);
        await expect(page.getByRole('button', {name: '模板批量应用'})).toHaveCount(0);
        // 行内无启停/编辑/删除，保留执行/预览SQL/详情
        await expect(row.getByLabel('停用')).toHaveCount(0);
        await expect(row.getByLabel('编辑')).toHaveCount(0);
        await expect(row.getByLabel('删除')).toHaveCount(0);
        await expect(row.getByLabel('详情')).toBeVisible();
    });
});
