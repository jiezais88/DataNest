import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {
    ADMIN,
    MAIN_SQL,
    PATH_PREFIX,
    PREFIX,
    cleanupS13,
    seedS13,
} from './helpers/seed';

/**
 * Sprint 13 自定义 SQL UI E2E：双形态向导（第 1 步形态选择 → 第 2 步自定义 SQL 定义
 * → 配置接口 → 绑定 Key）+ 列表形态列 + 详情 SQL 定义区块 + 编辑。
 *
 * 覆盖 PRD §9.1：AC-1（双形态向导）/ AC-2（SQL 编辑/参数识别/预览/保存）/
 * AC-3（只读校验行内提示）/ AC-6（详情 SQL 区块）。权限/闸门/调用等以 API 级
 * custom-sql-api.spec.ts 覆盖，本文件聚焦 UI 交互。
 *
 * 环境约定：前端 http://localhost:3000（nginx 代理 /api → gateway :8080）；
 * 数据自播种自清理（helpers/seed.ts）；串行执行。
 */

test.describe.configure({mode: 'serial'});

let sharedApiId = '';

test.beforeAll(async () => {
    await seedS13();
});

test.afterAll(async () => {
    await cleanupS13();
});

/** 进入向导并切到自定义 SQL 形态 */
async function gotoWizardCustomSql(page: Page): Promise<void> {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await expect(page.getByRole('heading', {name: '新建 API'})).toBeVisible();
    // 第 1 步：双形态单选卡片
    await expect(page.getByRole('heading', {name: '选择查询定义方式'})).toBeVisible();
    await expect(page.locator('label').filter({hasText: '选表'}).first()).toBeVisible();
    const customCard = page.locator('label').filter({hasText: '自定义 SQL'}).first();
    await customCard.click();
    await expect(customCard.locator('input[type="radio"]')).toBeChecked();
    await page.getByRole('button', {name: /下一步/}).click();
    // 第 2 步：自定义 SQL 表单
    await expect(page.getByText('SQL（只读）')).toBeVisible();
    await expect(page.getByRole('button', {name: '校验 SQL'})).toBeVisible();
    await expect(page.getByRole('button', {name: '试跑预览'})).toBeVisible();
}

/** 向 Monaco 编辑器输入 SQL（点击编辑器后 insertText） */
async function fillSqlEditor(page: Page, sql: string): Promise<void> {
    const editor = page.locator('.monaco-editor').first();
    await editor.click();
    await page.keyboard.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A');
    await page.keyboard.insertText(sql);
}

test('CS-UI-01 双形态向导：第 1 步出现「选表/自定义 SQL」单选，默认选表', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await expect(page.getByRole('heading', {name: '选择查询定义方式'})).toBeVisible();
    const tableCard = page.locator('label').filter({hasText: '选表'}).first();
    const customCard = page.locator('label').filter({hasText: '自定义 SQL'}).first();
    await expect(tableCard).toBeVisible();
    await expect(customCard).toBeVisible();
    // 默认选表
    await expect(tableCard.locator('input[type="radio"]')).toBeChecked();
    await expect(page.getByText(/机密表不可生成对外 API/)).toBeVisible(); // 安全提示条
});

test('CS-UI-02 自定义 SQL 定义：数据源单选（Doris）+ 编辑器 + 参数自动识别 + 涉及表', async ({page}) => {
    await gotoWizardCustomSql(page);
    // 数据源默认内置 Doris
    await expect(page.getByLabel('数据源')).toHaveValue('-1');
    // 输入 SQL（JOIN + :startDate）
    await fillSqlEditor(page, MAIN_SQL);
    await page.getByRole('button', {name: '校验 SQL'}).click();
    // 校验通过提示（识别参数 1 个 · 涉及表 2 张）
    await expect(page.getByText(/校验通过：识别参数 1 个/)).toBeVisible();
    // 参数表出现 :startDate（默认 STRING，可改类型）；Monaco 编辑器内也有同名文本，用 font-mono chip 精确定位
    await expect(page.locator('span.font-mono').filter({hasText: /^:startDate$/})).toBeVisible();
    await expect(page.getByLabel('参数 startDate 类型')).toHaveValue('STRING');
    // 涉及表 chips（同上：编辑器文本也会命中 getByText，限定 font-mono chip）
    await expect(page.locator('span.font-mono').filter({hasText: /^datanest\.e2e_s13_orders$/})).toBeVisible();
    await expect(page.locator('span.font-mono').filter({hasText: /^datanest\.e2e_s13_region$/})).toBeVisible();
    await expect(page.getByText(/共 2 张/)).toBeVisible();
});

test('CS-UI-03 只读校验：DELETE 语句行内报错，禁止进入下一步', async ({page}) => {
    await gotoWizardCustomSql(page);
    await fillSqlEditor(page, 'DELETE FROM datanest.e2e_s13_orders');
    await page.getByRole('button', {name: '校验 SQL'}).click();
    // 行内错误（红色提示，含「只读」）
    await expect(page.locator('.bg-ds-danger-light').first()).toBeVisible();
    await expect(page.locator('.bg-ds-danger-light').first()).toContainText(/只读|SELECT/);
    // 下一步被拦（门控先做只读预检：DELETE 直接弹只读错误 toast，优于「请先校验」提示）
    await page.getByRole('button', {name: /下一步/}).click();
    await expect(page.locator('.ant-message-notice-title')
        .getByText(/仅支持只读查询[（(]当前以 DELETE 开头/).first()).toBeVisible();
});

test('CS-UI-04 试跑预览：返回结果表（前 100 条）', async ({page}) => {
    await gotoWizardCustomSql(page);
    await fillSqlEditor(page, MAIN_SQL);
    // 先校验（识别参数，默认 STRING），将 startDate 类型改为 DATE，再试跑预览（日期示例值 2026-01-01）
    await page.getByRole('button', {name: '校验 SQL'}).click();
    await expect(page.getByText(/校验通过/)).toBeVisible();
    await page.getByLabel('参数 startDate 类型').selectOption('DATE');
    await page.getByRole('button', {name: '试跑预览'}).click();
    // 预览结果表出现（startDate 示例值 2026-01-01 → 全量 3 组）
    await expect(page.getByText(/预览结果/)).toBeVisible();
    await expect(page.locator('.prototype-table').first()).toBeVisible();
    await expect(page.getByText('SOUTH', {exact: true}).first()).toBeVisible();
});

test('CS-UI-05 向导完成：配置接口（自定义 SQL 无字段白名单）+ 新建 Key → 详情 SQL 定义区块', async ({page}) => {
    await gotoWizardCustomSql(page);
    await fillSqlEditor(page, MAIN_SQL);
    await page.getByRole('button', {name: '校验 SQL'}).click();
    await expect(page.getByText(/校验通过/)).toBeVisible();
    // startDate 改为 DATE：步骤 3 API 预览与后续调用均按日期示例值 2026-01-01
    // （改参数类型会重置 validated，需重新校验才能进入下一步）
    await page.getByLabel('参数 startDate 类型').selectOption('DATE');
    await page.getByRole('button', {name: '校验 SQL'}).click();
    await expect(page.getByText(/校验通过/)).toBeVisible();
    // 下一步：配置接口
    await page.getByRole('button', {name: /下一步/}).click();
    const nameInput = page.getByPlaceholder('例如：订单区域统计');
    const pathInput = page.getByPlaceholder('region-sum');
    await expect(nameInput).toBeVisible();
    await expect(pathInput).toBeVisible();
    // 自定义 SQL：无字段白名单区块
    await expect(page.getByText('请求方法')).toBeVisible();
    await expect(page.getByText('自定义 SQL API 固定为 GET（只读定位）')).toBeVisible();
    // API 预览含 SQL 参数
    await expect(page.getByText(/startDate=2026-01-01/)).toBeVisible();
    await nameInput.fill(`${PREFIX}UI区域汇总`);
    await pathInput.fill(`${PATH_PREFIX}ui-region-sum`);
    await page.getByRole('button', {name: /下一步/}).click();
    // 第 4 步：新建 Key
    await page.getByRole('radio', {name: /新建 Key/}).check();
    await page.getByPlaceholder('例如：业务-订单组').fill(`${PREFIX}UI向导Key`);
    await page.locator('input[type="number"]').fill('100');
    await page.getByRole('button', {name: '完成创建'}).click();
    // Key 明文弹窗
    const dialog = page.getByRole('dialog', {name: 'API Key 创建成功'});
    await expect(dialog).toBeVisible();
    const fullKey = (await dialog.locator('.font-mono').textContent())?.trim();
    expect(fullKey).toMatch(/^K-/);
    await dialog.getByRole('button', {name: '我已保存，前往 API 详情'}).click();
    await expect(page).toHaveURL(/\/data-service\/api-manage\/\d+/);
    const m = page.url().match(/\/data-service\/api-manage\/(\d+)/);
    expect(m).toBeTruthy();
    sharedApiId = m![1];
    // 详情：标题 + SQL 定义区块（SQL 文本 + 涉及表）
    await expect(page.getByRole('heading', {name: `${PREFIX}UI区域汇总`})).toBeVisible();
    await expect(page.getByText('SQL 定义', {exact: true})).toBeVisible();
    await expect(page.getByText(/FROM datanest\.e2e_s13_orders/)).toBeVisible();
    await expect(page.getByText(/datanest\.e2e_s13_orders/).first()).toBeVisible();
    await expect(page.getByText(/datanest\.e2e_s13_region/).first()).toBeVisible();
});

test('CS-UI-06 列表：形态列徽章 + 形态筛选（t7 前端）', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');
    // 列表行出现 CUSTOM_SQL 徽章（「自定义 SQL」文本）
    const row = page.locator('.ant-table-row').filter({hasText: `${PREFIX}UI区域汇总`});
    await expect(row).toBeVisible();
    await expect(row.getByText('自定义 SQL', {exact: true}).first()).toBeVisible();
    // 形态筛选下拉：选「自定义 SQL」→ 只剩 CUSTOM_SQL 行
    await page.getByLabel('按形态筛选').selectOption('CUSTOM_SQL');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row).toBeVisible();
    // 复位筛选
    await page.getByRole('button', {name: '重置'}).click();
});

test('CS-UI-07 编辑页：SQL 只读展示数据源 + 可改 SQL 重新校验', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${sharedApiId}`);
    await page.getByRole('button', {name: '编辑'}).click();
    await expect(page).toHaveURL(/\/edit$/);
    await expect(page.getByRole('heading', {name: '编辑 API'})).toBeVisible();
    // 数据源只读（页头 span + readOnly 表单 option 同名，取首个）
    await expect(page.getByText('Doris 数仓', {exact: true}).first()).toBeVisible();
    // SQL 编辑器预填（Monaco 内部文本不走 getByText，断言 view-lines 容器）
    await expect(page.locator('.monaco-editor .view-lines').first()).toContainText('FROM datanest.e2e_s13_orders');
    // 改成单表 SQL 并保存
    await fillSqlEditor(page, 'SELECT region_id, COUNT(*) AS cnt FROM datanest.e2e_s13_orders GROUP BY region_id');
    await page.getByRole('button', {name: '校验 SQL'}).click();
    await expect(page.getByText(/校验通过/)).toBeVisible();
    await page.getByRole('button', {name: '保存'}).click();
    await expect(page.getByText('API 已保存')).toBeVisible();
    await expect(page).toHaveURL(/\/data-service\/api-manage\/\d+$/);
});

test('CS-UI-08 选表流程回归：第 1 步选表形态走完三步无差异（AC-8 子集）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    // 默认选表形态 → 下一步 → 数据源/库/表
    await page.getByRole('button', {name: /下一步/}).click();
    await expect(page.getByText('数据表（单选）')).toBeVisible();
    await expect(page.getByLabel('数据源')).toHaveValue('-1');
    // 数据库选 datanest → 出现 e2e_s13_orders
    await page.getByLabel('数据库').selectOption('datanest');
    await expect(page.locator('label').filter({hasText: 'e2e_s13_orders'})).toBeVisible();
    // 选中表 → API 预览出现
    await page.locator('label').filter({hasText: 'e2e_s13_orders'}).click();
    await expect(page.getByText('/open-api/v1/e2e_s13_orders')).toBeVisible();
});

test('CS-UI-09 试跑预览空结果提示：参数未设默认值（示例值）且 0 行时给出原因', async ({page}) => {
    await gotoWizardCustomSql(page);
    await fillSqlEditor(page, MAIN_SQL); // :startDate 未校验/未设默认值 → 默认 STRING，示例值 '示例'
    await page.getByRole('button', {name: '试跑预览'}).click();
    // 预览结果 0 行 + 原因提示（日期列与 '示例' 比较查不到数据）
    await expect(page.getByText(/预览结果/).first()).toBeVisible();
    await expect(page.getByText(/结果为空：参数 :startDate 未设默认值/)).toBeVisible();
});
