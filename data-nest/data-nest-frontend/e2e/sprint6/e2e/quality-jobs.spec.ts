import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS, QUALITY_PREFIX, QUALITY_DS_NAME, QUALITY_SYNC_JOB} from '../helpers/data';
import {psql} from '../helpers/db';
import {gotoAs} from '../helpers/e2e';

/**
 * Sprint 6 质量任务 E2E 测试（页面：/governance/data-quality → 质量任务 Tab）
 * 覆盖：
 * - 页面加载：统计卡片 + Tab 切换
 * - 新增质量任务（Drawer：名称 / 数据源范围 / 启用）
 * - 定时调度：勾选不填 Cron → 校验错误；填 Cron 成功
 * - 自动触发完整绑定：勾选 + 选 SYNC_JOB + 选同步任务 → 成功，列表展示「同步任务」
 * - 详情查看（只读）
 * - 编辑任务
 * - 启停任务
 * - 筛选：关键字 / 状态
 * - 删除任务（ConfirmDialog，级联删规则）
 * - 权限：工程师可查看但不可编辑
 *
 * 测试数据前缀 e2e_s6_q，seed 由 seedQualityMetadata/seedSyncJob 提供元数据与同步任务。
 */

let admin: Api;

/** 测试元数据数据源 ID（与 seed.ts 一致） */
const QUALITY_DS_ID = '9000010000000000001';
/** 自动触发绑定用同步任务 ID（与 seed.ts 一致） */
const QUALITY_SYNC_JOB_ID = '9000010000000000003';

/** 定位当前行：按「任务名称」列精确匹配表格行 */
function rowBy(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({
        has: page.locator('.ant-table-cell:first-child').getByText(name, {exact: true}),
    });
}

/** 新增质量任务 Drawer：填写并保存（仅名称必填，其余可选；触发方式为按钮单选） */
async function fillJobCreateDrawer(
    page: Page,
    name: string,
    opts: { cron?: string; autoTriggerSyncJob?: boolean } = {},
) {
    const drawer = page.getByRole('dialog', {name: '新增质量任务'});
    await drawer.waitFor({state: 'visible', timeout: 10000});
    // 任务名称（placeholder 定位，唯一）
    await drawer.getByPlaceholder('例如：核心业务表完整性检查').fill(name);
    // 可选：Cron 定时（点「Cron 定时」按钮 + CronPicker 预设「每天凌晨 2 点」= 0 0 2 * * ?）
    if (opts.cron) {
        await drawer.getByRole('button', {name: 'Cron 定时'}).click();
        await drawer.getByRole('button', {name: '每天凌晨 2 点'}).click();
    }
    // 可选：自动触发绑定同步任务
    if (opts.autoTriggerSyncJob) {
        await drawer.getByRole('button', {name: '自动触发'}).click();
        // 绑定对象类型 → 同步任务
        await drawer
            .getByText('绑定对象类型', {exact: true})
            .locator('..')
            .locator('select')
            .selectOption('SYNC_JOB');
        // 同步任务下拉（label 含必填星号，用正则匹配）
        const syncSelect = drawer
            .getByText(/同步任务/)
            .locator('..')
            .locator('select');
        await syncSelect.waitFor({state: 'visible', timeout: 10000});
        await syncSelect.selectOption(QUALITY_SYNC_JOB_ID);
    }
    return drawer;
}

test.describe.configure({mode: 'serial'});

test.describe('Sprint 6 质量任务 E2E', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 清掉历史测试任务
        psql(`DELETE FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%'`);
        // 播种两个基础任务供筛选/详情/编辑/启停/删除测试
        await admin.post('/governance/quality/jobs', {
            name: `${QUALITY_PREFIX}_job1`, description: 's6 base enabled',
            datasourceId: QUALITY_DS_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 0,
            alertLevel: 'SEVERE_WARNING',
        });
        await admin.post('/governance/quality/jobs', {
            name: `${QUALITY_PREFIX}_job2`, description: 's6 base disabled',
            enabled: 0, scheduledEnabled: 0, autoTriggerEnabled: 0,
            alertLevel: 'SEVERE_ONLY',
        });
    });

    test.afterAll(async () => {
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%'`);
        await admin.dispose();
    });

    test('页面加载：质量任务列表', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        // 标题与基础任务出现
        await expect(page.getByRole('heading', {name: '质量任务'})).toBeVisible({timeout: 15000});
        await expect(rowBy(page, `${QUALITY_PREFIX}_job1`)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, `${QUALITY_PREFIX}_job2`)).toBeVisible();
    });

    test('新增质量任务：必填名称，保存后列表出现', async ({page}) => {
        const name = `${QUALITY_PREFIX}_新增_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        await page.getByRole('button', {name: '新增质量任务'}).first().click();
        const drawer = await fillJobCreateDrawer(page, name);
        await drawer.getByRole('button', {name: '保存'}).click();
        await expect(drawer).toHaveCount(0, {timeout: 10000});
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('启用', {exact: true})).toBeVisible();
    });

    test('新增质量任务：Cron 定时但未填 Cron → 校验错误', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        await page.getByRole('button', {name: '新增质量任务'}).first().click();
        const drawer = page.getByRole('dialog', {name: '新增质量任务'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        await drawer.getByPlaceholder('例如：核心业务表完整性检查').fill(`${QUALITY_PREFIX}_cron_bad_${Date.now()}`);
        // 点「Cron 定时」按钮但不填 Cron
        await drawer.getByRole('button', {name: 'Cron 定时'}).click();
        await drawer.getByRole('button', {name: '保存'}).click();
        await expect(drawer.getByText('开启定时调度时请输入 Cron 表达式')).toBeVisible();
        // Drawer 未关闭
        await expect(drawer).toBeVisible();
        await drawer.getByRole('button', {name: '取消'}).click();
    });

    test('新增质量任务：定时调度 + Cron 保存成功', async ({page}) => {
        const name = `${QUALITY_PREFIX}_cron_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        await page.getByRole('button', {name: '新增质量任务'}).first().click();
        const drawer = await fillJobCreateDrawer(page, name, {cron: '0 0 2 * * ?'});
        await drawer.getByRole('button', {name: '保存'}).click();
        await expect(rowBy(page, name)).toBeVisible({timeout: 15000});
        // 定时调度列展示 Cron
        await expect(rowBy(page, name).getByText('0 0 2 * * ?')).toBeVisible();
    });

    test('新增质量任务：自动触发完整绑定同步任务', async ({page}) => {
        const name = `${QUALITY_PREFIX}_auto_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        await page.getByRole('button', {name: '新增质量任务'}).first().click();
        const drawer = await fillJobCreateDrawer(page, name, {autoTriggerSyncJob: true});
        await drawer.getByRole('button', {name: '保存'}).click();
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        // 自动触发列展示「同步任务」
        await expect(row.getByText('同步任务', {exact: true})).toBeVisible();
    });

    test('详情查看：只读展示任务信息', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        const row = rowBy(page, `${QUALITY_PREFIX}_job1`);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('详情').click();
        const drawer = page.getByRole('dialog', {name: '质量任务详情'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        // 只读：无保存/取消按钮，名称 input 禁用
        await expect(drawer.getByRole('button', {name: '保存'})).toHaveCount(0);
        await expect(drawer.getByPlaceholder('例如：核心业务表完整性检查')).toBeDisabled();
    });

    test('编辑任务：修改名称并保存', async ({page}) => {
        const oldName = `${QUALITY_PREFIX}_job1`;
        const newName = `${QUALITY_PREFIX}_job1_改_${Date.now()}`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        const row = rowBy(page, oldName);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('编辑').click();
        const drawer = page.getByRole('dialog', {name: '编辑质量任务'});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        await drawer.getByPlaceholder('例如：核心业务表完整性检查').fill(newName);
        await drawer.getByRole('button', {name: '保存'}).click();
        await expect(rowBy(page, newName)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, oldName)).toHaveCount(0);
        // 改回，避免影响后续用例
        await rowBy(page, newName).getByLabel('编辑').click();
        const drawer2 = page.getByRole('dialog', {name: '编辑质量任务'});
        await drawer2.waitFor({state: 'visible', timeout: 10000});
        await drawer2.getByPlaceholder('例如：核心业务表完整性检查').fill(oldName);
        await drawer2.getByRole('button', {name: '保存'}).click();
        await expect(rowBy(page, oldName)).toBeVisible({timeout: 15000});
    });

    test('启停任务：停用后状态变停用，可恢复', async ({page}) => {
        const name = `${QUALITY_PREFIX}_job1`;
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await expect(row.getByText('启用', {exact: true})).toBeVisible();
        // 停用
        await row.getByLabel('停用').click();
        await expect(row.getByText('停用', {exact: true})).toBeVisible({timeout: 10000});
        // 恢复启用
        await row.getByLabel('启用').click();
        await expect(row.getByText('启用', {exact: true})).toBeVisible({timeout: 10000});
    });

    test('筛选：关键字搜索任务名称', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        await page.getByLabel('搜索').fill('job1');
        await page.getByRole('button', {name: '查询'}).click();
        await expect(rowBy(page, `${QUALITY_PREFIX}_job1`)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, `${QUALITY_PREFIX}_job2`)).toHaveCount(0);
        await page.getByRole('button', {name: '重置'}).click();
        await expect(rowBy(page, `${QUALITY_PREFIX}_job2`)).toBeVisible({timeout: 15000});
    });

    test('筛选：按状态筛选已停用', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        await page.getByLabel('按状态筛选').selectOption('0');
        await page.getByRole('button', {name: '查询'}).click();
        // 只显示停用任务（job2），job1 被过滤
        await expect(rowBy(page, `${QUALITY_PREFIX}_job2`)).toBeVisible({timeout: 15000});
        await expect(rowBy(page, `${QUALITY_PREFIX}_job1`)).toHaveCount(0);
        await page.getByRole('button', {name: '重置'}).click();
    });

    test('删除任务：ConfirmDialog 确认删除', async ({page}) => {
        const name = `${QUALITY_PREFIX}_待删_${Date.now()}`;
        await admin.post('/governance/quality/jobs', {
            name, description: 's6 to delete', enabled: 1,
            scheduledEnabled: 0, autoTriggerEnabled: 0, alertLevel: 'SEVERE_WARNING',
        });
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/data-quality');
        const row = rowBy(page, name);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('删除').click();
        const dialog = page.getByRole('dialog', {name: '删除确认'});
        await dialog.waitFor({state: 'visible', timeout: 10000});
        await dialog.getByRole('button', {name: /确认删除/}).click();
        await expect(row).toHaveCount(0, {timeout: 10000});
    });

    test('权限：工程师可查看但不可编辑质量任务', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/governance/data-quality');
        await expect(page.getByRole('heading', {name: '质量任务'})).toBeVisible();
        const row = rowBy(page, `${QUALITY_PREFIX}_job1`);
        await expect(row).toBeVisible({timeout: 15000});
        // 无新增按钮
        await expect(page.getByRole('button', {name: '新增质量任务'})).toHaveCount(0);
        // 行内无启停/编辑/删除（只保留执行/详情）
        await expect(row.getByLabel('停用')).toHaveCount(0);
        await expect(row.getByLabel('编辑')).toHaveCount(0);
        await expect(row.getByLabel('删除')).toHaveCount(0);
        await expect(row.getByLabel('详情')).toBeVisible();
    });
});
