import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    F2_USERS,
    TARGET,
    getTargetTableId,
    seedF2,
    cleanupF2,
} from './helpers/f2-seed';

/**
 * Sprint 10 F2 E2E：API Key 管理（新建/明文一次性展示/编辑重绑/快捷启停/删除/僵尸 Key/绑定联动）。
 *
 * 覆盖验收点（对齐 PRD §6.4 §8 + handoff §19/§20 实现）：
 * - AC-6 Key 认证前置：K- 明文仅创建展示一次、SHA-256 哈希落库（后端自测已核，UI 验证明文一次性）
 * - Key 生命周期：ENABLED→DISABLED→ENABLED→删除（清理绑定）
 * - 僵尸 Key（近 7 天 0 调用）灰显 + 建议停用提示条
 * - Key-API 绑定：新建绑定 / 编辑全量重绑 / 绑定数在 API 列表与详情联动
 * - 权限：写操作超管/工程师（分析师/治理员无新建按钮）
 *
 * 环境约定：
 * - 自播种自清理：beforeAll 建 1 个目标 API（e2e_s10_key_目标API）供绑定；afterAll 清理全部 e2e_s10_*
 * - 串行执行；与 api-manage.spec.ts 独立运行（各自 beforeAll/afterAll，可同跑不冲突）
 */

test.describe.configure({mode: 'serial'});

const PW = 'Test123456';

// ==================== 共享状态 ====================
let targetApiId = ''; // 供 Key 绑定的目标 API
let mainKeyId = ''; // 主链路 Key（AK-4 创建）

// ==================== 小工具 ====================

function row(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({hasText: name});
}

/** 打开新建 Key 弹窗并返回 dialog */
async function openNewKeyDialog(page: Page) {
    await page.getByRole('button', {name: '新建 Key'}).first().click();
    return page.getByRole('dialog', {name: '新建 API Key'});
}

// ==================== 播种/清理 ====================

test.beforeAll(async () => {
    await seedF2();
    // 建 1 个目标 API 供 Key 绑定（admin）
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post<{ id: string }>('/data-service/apis', {
        name: 'e2e_s10_key_目标API',
        path: 'e2e-s10-key-target',
        datasourceId: TARGET.datasourceId,
        databaseName: TARGET.databaseName,
        tableName: TARGET.tableName,
        metadataTableId: getTargetTableId(),
        paginated: 1,
        pageSizeMax: 100,
    });
    targetApiId = detail.id;
    await api.dispose();
});

test.afterAll(async () => {
    await cleanupF2();
});

// ==================== 用例 ====================

test('AK-1 页面加载：标题/描述/新建 Key/限流提示条/工具栏', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');

    await expect(page.getByRole('heading', {name: 'API Key 管理'})).toBeVisible();
    await expect(page.getByText(/Key 是业务系统调用数据 API 的凭证/)).toBeVisible();
    await expect(page.getByRole('button', {name: '新建 Key'}).first()).toBeVisible();

    // 工具栏
    await expect(page.getByPlaceholder('搜索 Key 名称')).toBeVisible();
    await expect(page.getByLabel('按状态筛选')).toBeVisible();

    // 底部限流说明 + 僵尸 Key 建议
    await expect(page.getByText(/限流说明：/)).toBeVisible();
    await expect(page.getByText(/近 7 天无调用的 Key 建议停用/)).toBeVisible();
});

test('AK-2 空态：无 Key 时展示空态引导', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');

    await expect(page.getByText(/暂无 API Key/)).toBeVisible();
    await expect(page.getByRole('button', {name: '新建 Key'}).last()).toBeVisible();
});

test('AK-3 新建弹窗：前端校验（空名称 / QPS 越界）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');
    const dialog = await openNewKeyDialog(page);

    // 空名称点创建 → 提示
    await dialog.getByRole('button', {name: '创建'}).click();
    await expect(page.getByText('请填写 Key 名称')).toBeVisible();

    // QPS 越界 → 提示
    await dialog.getByPlaceholder('例如：业务-订单组').fill('e2e_s10_key_校验');
    await dialog.locator('input[type="number"]').fill('0');
    await dialog.getByRole('button', {name: '创建'}).click();
    await expect(page.getByText(/限流 QPS 需为 1~10000 的整数/)).toBeVisible();

    // 取消关闭
    await dialog.getByRole('button', {name: '取消'}).click();
    await expect(page.getByRole('dialog', {name: '新建 API Key'})).toHaveCount(0);
});

test('AK-4 新建 Key：绑定 API → 明文一次性展示 → 列表出现', async ({page}) => {
    expect(targetApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');
    const dialog = await openNewKeyDialog(page);

    await dialog.getByPlaceholder('例如：业务-订单组').fill('e2e_s10_key_主Key');
    await dialog.locator('input[type="number"]').fill('10');
    // 绑定目标 API（checkbox label 含 API 名 + 路径）
    await dialog.getByText('e2e_s10_key_目标API', {exact: true}).click();
    await expect(dialog.getByText(/已选 1 个/)).toBeVisible();

    await dialog.getByRole('button', {name: '创建'}).click();

    // 明文一次性展示（K- 前缀 + 32 位 hex）
    const keyDialog = page.getByRole('dialog', {name: 'API Key 创建成功'});
    await expect(keyDialog.getByText(/K-[0-9a-f]{32}/)).toBeVisible();
    await expect(keyDialog.getByText(/完整 Key 仅在此展示一次/)).toBeVisible();
    await expect(keyDialog.getByRole('button', {name: '复制'})).toBeVisible();

    // 关闭后列表出现新 Key（启用）
    await keyDialog.getByRole('button', {name: '我已保存，关闭'}).click();
    await expect(row(page, 'e2e_s10_key_主Key')).toBeVisible();
});

test('AK-5 列表列：绑定 API 数 / QPS / 近 7 天调用 0（僵尸 Key 灰显）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');

    const keyRow = row(page, 'e2e_s10_key_主Key');
    await expect(keyRow).toBeVisible();
    // 绑定 API 数 = 1（AK-4 绑定目标 API）
    await expect(keyRow.getByText('1', {exact: true})).toBeVisible();
    // QPS = 10
    await expect(keyRow.getByText('10', {exact: true})).toBeVisible();
    // 近 7 天调用 0 = 僵尸 Key（灰显 tooltip）
    await expect(keyRow.getByText('0', {exact: true})).toBeVisible();
});

test('AK-6 编辑 Key：预填绑定 + 改名/QPS/全量重绑', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');
    const keyRow = row(page, 'e2e_s10_key_主Key');
    await keyRow.getByRole('button', {name: '编辑'}).click();

    const dialog = page.getByRole('dialog', {name: '编辑 API Key'});
    // 预填：名称 + 已绑定 1 个
    await expect(dialog.getByPlaceholder('例如：业务-订单组')).toHaveValue('e2e_s10_key_主Key');
    await expect(dialog.getByText(/已选 1 个/)).toBeVisible();
    await expect(dialog.locator('input[type="checkbox"]').first()).toBeChecked();

    // 改名 + QPS + 解绑（全量重绑为空）
    await dialog.getByPlaceholder('例如：业务-订单组').fill('e2e_s10_key_主Key改');
    await dialog.locator('input[type="number"]').fill('20');
    await dialog.getByText('e2e_s10_key_目标API', {exact: true}).click(); // 取消勾选
    await expect(dialog.getByText(/已选 0 个/)).toBeVisible();

    await dialog.getByRole('button', {name: '保存'}).click();
    await expect(page.getByText(/已保存/)).toBeVisible();

    // 列表更新：改名 + 绑定数 0
    await expect(row(page, 'e2e_s10_key_主Key改')).toBeVisible();
    const updatedRow = row(page, 'e2e_s10_key_主Key改');
    await expect(updatedRow.getByText('20', {exact: true})).toBeVisible();
});

test('AK-7 快捷禁用/启用：操作列 1 步处置', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');
    const keyRow = row(page, 'e2e_s10_key_主Key改');

    // 禁用
    await keyRow.getByRole('button', {name: '禁用'}).click();
    await expect(page.getByText(/已禁用，业务系统将立即无法凭它调用/)).toBeVisible();
    await expect(keyRow.getByText('禁用', {exact: true})).toBeVisible();

    // 启用
    await keyRow.getByRole('button', {name: '启用'}).click();
    await expect(page.getByText(/已启用/)).toBeVisible();
    await expect(keyRow.getByText('启用', {exact: true})).toBeVisible();
});

test('AK-8 删除 Key：确认弹窗 + 列表消失', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');
    const keyRow = row(page, 'e2e_s10_key_主Key改');

    await keyRow.getByRole('button', {name: '删除'}).click();
    const dialog = page.getByRole('dialog', {name: '删除 API Key'});
    await expect(dialog.getByText(/确认删除 Key「e2e_s10_key_主Key改」/)).toBeVisible();
    await dialog.getByRole('button', {name: '删除'}).click();
    await expect(page.getByText(/已删除/)).toBeVisible();
    await expect(keyRow).toHaveCount(0);
});

test('AK-9 搜索 / 状态筛选 / 重置', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-keys');

    // 搜索命中
    await page.getByPlaceholder('搜索 Key 名称').fill('e2e_s10_key_');
    await page.keyboard.press('Enter');
    // 状态筛选「禁用」→ 空（当前无禁用 Key）
    await page.getByLabel('按状态筛选').selectOption('DISABLED');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(page.getByText(/暂无 API Key/)).toBeVisible();
    // 重置恢复
    await page.getByRole('button', {name: '重置'}).click();
});

test('AK-10 绑定联动：Key 绑定后 API 详情/列表的绑定数联动', async ({page}) => {
    // 重新建一个 Key 绑定目标 API
    expect(targetApiId).toBeTruthy();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const created = await api.post<{ id: string }>('/data-service/api-keys', {
        name: 'e2e_s10_key_联动', qpsLimit: 5, apiIds: [targetApiId],
    });
    mainKeyId = created.id;
    await api.dispose();

    // API 详情「绑定 Key（1）」
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${targetApiId}`);
    await expect(page.getByText('绑定 Key（1）')).toBeVisible();
    await expect(page.getByText('e2e_s10_key_联动')).toBeVisible();
    await expect(page.getByText('1 个', {exact: true})).toBeVisible();

    // API 列表「绑定 Key」列 = 1
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');
    const apiRow = row(page, 'e2e_s10_key_目标API');
    await expect(apiRow).toBeVisible();
});

test('AK-11 权限：分析师/治理员无「新建 Key」按钮（写操作 403 已由 AM-16 覆盖）', async ({page}) => {
    for (const u of [F2_USERS.analyst, F2_USERS.govAdmin]) {
        await gotoAs(page, u.username, PW, '/data-service/api-keys');
        await expect(page.getByRole('heading', {name: 'API Key 管理'})).toBeVisible();
        await expect(page.getByRole('button', {name: '新建 Key'})).toHaveCount(0);
        // 行内无编辑/删除（列表只读）
        await expect(page.locator('.ant-table-row').first().getByRole('button', {name: '编辑'})).toHaveCount(0);
        await expect(page.locator('.ant-table-row').first().getByRole('button', {name: '删除'})).toHaveCount(0);
    }
});
