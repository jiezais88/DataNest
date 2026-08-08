import {expect, type Page, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {psqlEng, psqlGov} from '../helpers/db';
import {cleanupTaskTemplateFixtures, seedAll} from '../helpers/seed';
import {
    TEST_USERS,
    DS_NAME,
    TPL_FULL_SYNC_NAME,
    TPL_INCR_SYNC_NAME,
    TPL_COLLECT_NAME,
    TPL_SRC_JOB_NAME,
} from '../helpers/data';

/**
 * Sprint 7 F2 任务模板库 E2E 测试（业务流程全覆盖，API/DB 辅助诊断）。
 *
 * 覆盖：列表展示（内置 3 条/列完整/计数文案）、segmented 类型过滤、权限隔离
 * （ENGINEERING_WRITE_ROLES，分析师不可见不可调）、一键创建（SYNC 占位符表单/前端必填校验/
 * 默认值预填/COLLECT 跨服务落库）、模板 CRUD（手动 JSON 新增/重名 7302/另存为占位化/
 * 编辑类型锁定/复制内置为自定义/删除/内置只读按钮集）。
 *
 * 测试数据：seedAll 播种（含 F2 fixture「另存为候选 sync_job」e2e_s7_tpl_source_sync）。
 * 本 spec 产生的模板/任务均以 e2e_s7 前缀命名，afterAll 经 DB 兜底清理。
 */

let engineer: Api;
let analyst: Api;

/** 模板列表行（按模板名） */
function tplRow(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({hasText: name});
}

/** 居中弹窗（一键创建 CreateTaskModal） */
function createDialog(page: Page) {
    return page.locator('div[role="dialog"]').filter({hasText: '从模板创建'});
}

/** 右侧抽屉（模板表单 TemplateFormDrawer） */
function formDrawer(page: Page) {
    return page.locator('div[role="dialog"]').filter({has: page.locator('h2')});
}

/** antd 通知断言 */
function notice(page: Page, text: string | RegExp) {
    return page.locator('.ant-message-notice').filter({hasText: text}).first();
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    // 本 spec 自带播种（幂等），支持 SKIP_SETUP=1 独立运行
    await seedAll();
    engineer = await Api.create();
    await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
    analyst = await Api.create();
    await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
});

test.afterAll(async () => {
    // DB 兜底清理本 spec 产生的模板/任务
    cleanupTaskTemplateFixtures();
    await engineer?.dispose();
    await analyst?.dispose();
});

// ==================== 列表与筛选 ====================

test.describe('列表展示与过滤', () => {
    test('列表页：内置 3 条模板 + 列完整 + 计数文案', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        await expect(page.getByRole('heading', {name: '任务模板库'})).toBeVisible();

        // 内置 3 条（Flyway 播种）
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toBeVisible();
        await expect(tplRow(page, TPL_INCR_SYNC_NAME)).toBeVisible();
        await expect(tplRow(page, TPL_COLLECT_NAME)).toBeVisible();

        // 列内容：类型 mono badge / 内置徽章 / 占位参数 / 状态 / 创建人「系统」
        const fullSync = tplRow(page, TPL_FULL_SYNC_NAME);
        await expect(fullSync.getByText('SYNC', {exact: true})).toBeVisible();
        await expect(fullSync.getByText('内置', {exact: true})).toBeVisible();
        await expect(fullSync.getByText(/\{source_datasource\}/)).toBeVisible();
        await expect(fullSync.getByText('启用', {exact: true})).toBeVisible();
        await expect(fullSync.getByText('系统', {exact: true})).toBeVisible();
        await expect(tplRow(page, TPL_COLLECT_NAME).getByText('COLLECT', {exact: true})).toBeVisible();

        // 计数文案（本测试运行时尚无自定义模板）
        await expect(page.getByText(/内置 3 · 自定义 \d+/)).toBeVisible();

        // 底部说明条
        await expect(page.getByText(/模板被删除不影响已创建任务/)).toBeVisible();
    });

    test('segmented 类型过滤：全部 / 同步任务 / 采集任务', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');

        await page.getByRole('button', {name: '同步任务', exact: true}).click();
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toBeVisible();
        await expect(tplRow(page, TPL_COLLECT_NAME)).toHaveCount(0);

        await page.getByRole('button', {name: '采集任务', exact: true}).click();
        await expect(tplRow(page, TPL_COLLECT_NAME)).toBeVisible();
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toHaveCount(0);

        await page.getByRole('button', {name: '全部', exact: true}).click();
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toBeVisible();
        await expect(tplRow(page, TPL_COLLECT_NAME)).toBeVisible();
    });

    test('权限隔离：分析师无侧边栏入口 + API 1005 + 页面无数据', async ({page}) => {
        // API 辅助诊断：分析师直接调列表接口应 1005
        const env = await analyst.raw('GET', '/engineering/task-templates');
        expect(env.code).toBe(1005);

        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password,
            '/engineering/task-templates');
        // 侧边栏无「任务模板库」入口
        await expect(page.getByRole('button', {name: '任务模板库'})).toHaveCount(0);
        // 直接访问 URL：列表加载失败，无模板行
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toHaveCount(0);
    });
});

// ==================== 一键创建（主流程） ====================

test.describe('一键创建任务', () => {
    test('SYNC 整表同步：占位符表单 + 前端必填校验 + 落库验证', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        await tplRow(page, TPL_FULL_SYNC_NAME).getByLabel('一键创建').click();
        const dialog = createDialog(page);
        await expect(dialog).toBeVisible();

        // 占位符表单渲染（5 个：源数据源下拉 + 源库/源表/目标库/目标表文本）
        await expect(dialog.getByText('（{source_datasource}）')).toBeVisible();
        await expect(dialog.getByText('（{source_table}）')).toBeVisible();
        await expect(dialog.getByText('（{target_table}）')).toBeVisible();

        // 前端必填校验：任务名称空 → 拦截
        await dialog.getByRole('button', {name: '生成任务'}).click();
        await expect(notice(page, '请输入任务名称')).toBeVisible();

        // 任务名称填了但必填占位符空 → 拦截（第一个必填是源数据源）
        await dialog.getByPlaceholder('如：dwd_orders 每日同步').fill('e2e_s7_task_full_sync');
        await dialog.getByRole('button', {name: '生成任务'}).click();
        await expect(notice(page, /请填写「源数据源」/)).toBeVisible();

        // 填全：数据源下拉选 e2e_s7_mysql_ds + 4 个文本占位符
        await dialog.locator('.ant-select').first().click();
        await page.locator('.ant-select-dropdown .ant-select-item', {hasText: DS_NAME}).first().click();
        const textInputs = dialog.locator('input.font-mono');
        await textInputs.nth(0).fill('testdb'); // source_db
        await textInputs.nth(1).fill('e2e_s7_src_orders'); // source_table
        await textInputs.nth(2).fill('dwd'); // target_db
        await textInputs.nth(3).fill('e2e_s7_tgt_full'); // target_table
        await dialog.getByRole('button', {name: '生成任务'}).click();
        await expect(notice(page, /已创建同步任务/)).toBeVisible();

        // DB 辅助验证：sync_job 落库且占位符已替换为填写值
        const name = psqlEng(`SELECT name FROM sync_job WHERE name = 'e2e_s7_task_full_sync'`);
        expect(name).toBe('e2e_s7_task_full_sync');
        const targetTable = psqlEng(`SELECT target_table FROM sync_job WHERE name = 'e2e_s7_task_full_sync'`);
        expect(targetTable).toBe('e2e_s7_tgt_full');
        const sourceDs = psqlEng(`SELECT source_datasource_id FROM sync_job WHERE name = 'e2e_s7_task_full_sync'`);
        expect(sourceDs).not.toContain('{source_datasource}');
    });

    test('SYNC 增量同步：schedule_cron 默认值预填 + cron 落库', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        await tplRow(page, TPL_INCR_SYNC_NAME).getByLabel('一键创建').click();
        const dialog = createDialog(page);
        await expect(dialog).toBeVisible();

        // schedule_cron 非必填带默认值 0 0 2 * * ?（预填）
        const cronInput = dialog.locator('input.font-mono').last();
        await expect(cronInput).toHaveValue('0 0 2 * * ?');

        await dialog.getByPlaceholder('如：dwd_orders 每日同步').fill('e2e_s7_task_incr_sync');
        await dialog.locator('.ant-select').first().click();
        await page.locator('.ant-select-dropdown .ant-select-item', {hasText: DS_NAME}).first().click();
        const textInputs = dialog.locator('input.font-mono');
        // 顺序：source_db / source_table / incremental_field / target_db / target_table / schedule_cron(已预填)
        await textInputs.nth(0).fill('testdb');
        await textInputs.nth(1).fill('e2e_s7_src_orders');
        await textInputs.nth(2).fill('updated_at');
        await textInputs.nth(3).fill('dwd');
        await textInputs.nth(4).fill('e2e_s7_tgt_incr');
        await dialog.getByRole('button', {name: '生成任务'}).click();
        await expect(notice(page, /已创建同步任务/)).toBeVisible();

        const cron = psqlEng(`SELECT cron_expression FROM sync_job WHERE name = 'e2e_s7_task_incr_sync'`);
        expect(cron).toBe('0 0 2 * * ?');
        const mode = psqlEng(`SELECT sync_mode FROM sync_job WHERE name = 'e2e_s7_task_incr_sync'`);
        expect(mode).toBe('INCREMENTAL');
    });

    test('COLLECT 元数据全量采集：跨服务落 collect_task', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        await tplRow(page, TPL_COLLECT_NAME).getByLabel('一键创建').click();
        const dialog = createDialog(page);
        await expect(dialog).toBeVisible();

        await dialog.getByPlaceholder('如：dwd_orders 每日同步').fill('e2e_s7_task_collect');
        await dialog.locator('.ant-select').first().click();
        await page.locator('.ant-select-dropdown .ant-select-item', {hasText: DS_NAME}).first().click();
        // 采集范围 scope（文本占位符）
        await dialog.locator('input.font-mono').first().fill('testdb');
        await dialog.getByRole('button', {name: '生成任务'}).click();
        await expect(notice(page, /已创建采集任务/)).toBeVisible();

        // DB 辅助验证：collect_task 落 governance 库
        const name = psqlGov(`SELECT name FROM collect_task WHERE name = 'e2e_s7_task_collect'`);
        expect(name).toBe('e2e_s7_task_collect');
    });
});

// ==================== 模板 CRUD ====================

test.describe('模板 CRUD', () => {
    test('新增自定义模板（手动 JSON）→ 列表出现（自定义徽章 + 创建人）', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        // 等首行数据渲染完再点（加载瞬间空态里也有同名按钮，会撞严格模式）
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toBeVisible();
        await page.getByRole('button', {name: '新增自定义模板'}).click();
        const drawer = formDrawer(page);
        await expect(drawer.getByRole('heading', {name: '新增自定义模板'})).toBeVisible();

        await drawer.getByPlaceholder('如：订单表每日同步').fill('e2e_s7_custom_sync');
        await drawer.locator('textarea[placeholder*="placeholders"]').fill(JSON.stringify({
            placeholders: [{key: 'target_table', label: '目标表', required: true}],
            config: {
                sourceDatasourceId: '1', sourceDatabase: 'testdb', sourceTables: ['e2e_s7_src_orders'],
                syncMode: 'FULL', triggerType: 'MANUAL', targetDatabase: 'dwd', targetTable: '{target_table}',
            },
        }));
        await drawer.getByRole('button', {name: '保存模板'}).click();
        await expect(notice(page, '模板已创建')).toBeVisible();

        const row = tplRow(page, 'e2e_s7_custom_sync');
        await expect(row).toBeVisible();
        await expect(row.getByText('自定义', {exact: true})).toBeVisible();
        await expect(row.getByText(TEST_USERS.engineer.username, {exact: true})).toBeVisible();
        // 自定义行有编辑/删除按钮
        await expect(row.getByLabel('编辑')).toBeVisible();
        await expect(row.getByLabel('删除')).toBeVisible();
    });

    test('重名模板被拦截（7302）', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        // 等首行数据渲染完再点（加载瞬间空态里也有同名按钮，会撞严格模式）
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toBeVisible();
        await page.getByRole('button', {name: '新增自定义模板'}).click();
        const drawer = formDrawer(page);
        await drawer.getByPlaceholder('如：订单表每日同步').fill('e2e_s7_custom_sync');
        await drawer.locator('textarea[placeholder*="placeholders"]').fill('{"placeholders":[],"config":{}}');
        await drawer.getByRole('button', {name: '保存模板'}).click();
        // 后端 7302 动态 message 由拦截器弹出（含「已存在」）
        await expect(notice(page, /已存在/)).toBeVisible();
        // 抽屉未关闭（可继续修改）
        await expect(drawer).toBeVisible();
        await drawer.getByLabel('关闭').click();
    });

    test('从已配置任务另存：选任务隐藏 JSON 框 + 自动占位化', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        // 等首行数据渲染完再点（加载瞬间空态里也有同名按钮，会撞严格模式）
        await expect(tplRow(page, TPL_FULL_SYNC_NAME)).toBeVisible();
        await page.getByRole('button', {name: '新增自定义模板'}).click();
        const drawer = formDrawer(page);
        await drawer.getByPlaceholder('如：订单表每日同步').fill('e2e_s7_saveas_tpl');

        // 选择另存候选任务后 JSON 文本框隐藏
        await drawer.locator('select[aria-label="从已配置任务另存"]').selectOption({label: TPL_SRC_JOB_NAME});
        await expect(drawer.locator('textarea[placeholder*="placeholders"]')).toHaveCount(0);

        await drawer.getByRole('button', {name: '保存模板'}).click();
        await expect(notice(page, '模板已创建')).toBeVisible();

        // 单表 SYNC 另存为自动抽 {source_table} 占位符（列表占位参数列可见）
        await expect(tplRow(page, 'e2e_s7_saveas_tpl').getByText(/\{source_table\}/)).toBeVisible();
    });

    test('编辑模板：类型锁定 + 改名保存（配置保留）', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        await tplRow(page, 'e2e_s7_custom_sync').getByLabel('编辑').click();
        const drawer = formDrawer(page);
        await expect(drawer.getByRole('heading', {name: /编辑模板/})).toBeVisible();

        // 类型下拉锁定
        await expect(drawer.locator('select[aria-label="任务类型"]')).toBeDisabled();

        await drawer.getByPlaceholder('如：订单表每日同步').fill('e2e_s7_renamed_tpl');
        await drawer.getByRole('button', {name: '保存模板'}).click();
        await expect(notice(page, '模板已保存')).toBeVisible();
        await expect(tplRow(page, 'e2e_s7_renamed_tpl')).toBeVisible();
        await expect(tplRow(page, 'e2e_s7_custom_sync')).toHaveCount(0);
    });

    test('复制内置为自定义：名称/配置预填 + 保存', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        const builtinRow = tplRow(page, TPL_FULL_SYNC_NAME);
        // 内置模板只读：有复制按钮，无编辑/删除按钮
        await expect(builtinRow.getByLabel('复制为自定义')).toBeVisible();
        await expect(builtinRow.getByLabel('编辑')).toHaveCount(0);
        await expect(builtinRow.getByLabel('删除')).toHaveCount(0);

        await builtinRow.getByLabel('复制为自定义').click();
        const drawer = formDrawer(page);
        await expect(drawer.getByRole('heading', {name: '复制为自定义模板'})).toBeVisible();
        // 名称预填「整表同步 副本」、配置 JSON 预填
        await expect(drawer.getByPlaceholder('如：订单表每日同步')).toHaveValue(`${TPL_FULL_SYNC_NAME} 副本`);
        await expect(drawer.locator('textarea[placeholder*="placeholders"]')).not.toHaveValue('');

        await drawer.getByPlaceholder('如：订单表每日同步').fill('e2e_s7_copied_tpl');
        await drawer.getByRole('button', {name: '保存模板'}).click();
        await expect(notice(page, '已复制为自定义模板')).toBeVisible();
        await expect(tplRow(page, 'e2e_s7_copied_tpl').getByText('自定义', {exact: true})).toBeVisible();
    });

    test('删除自定义模板 → 列表消失', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password,
            '/engineering/task-templates');
        await tplRow(page, 'e2e_s7_copied_tpl').getByLabel('删除').click();
        const confirm = page.locator('div[role="dialog"]').filter({hasText: '删除模板'});
        await expect(confirm).toBeVisible();
        await confirm.getByRole('button', {name: '删除'}).click();
        await expect(notice(page, /已删除模板/)).toBeVisible();
        await expect(tplRow(page, 'e2e_s7_copied_tpl')).toHaveCount(0);
    });
});
