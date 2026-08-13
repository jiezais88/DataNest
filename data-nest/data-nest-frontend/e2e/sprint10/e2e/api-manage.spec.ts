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
    setSensitivity,
} from './helpers/f2-seed';

/**
 * Sprint 10 F2 E2E：数据 API 管理端（列表 / 创建向导 / 详情 / 编辑 / 生命周期 / 权限 / 敏感度闸门）。
 *
 * 覆盖验收点（对齐 PRD §6.3 §8 §9.1 + handoff §19/§20 实现）：
 * - AC-5 API 创建：公开表一键生成、参数化/分页/排序配置生效、自动文档预览；机密表禁选 + 9004 拦截
 * - 生命周期：CREATED→PUBLISHED→DISABLED→软删（路径复用）
 * - 权限矩阵：查看四角色、写操作超管/工程师（分析师/治理员 403）
 * - 敏感度闸门（fail-closed 三处：创建/编辑/发布）：机密 9004、内部表开白放行
 * - 列表：统计卡下钻 / 搜索 / 状态筛选 / 我的 API 切换
 *
 * 环境约定：
 * - 前端 http://localhost:3000（nginx 代理 /api → gateway http://localhost:8080）
 * - 测试数据自播种自清理（helpers/f2-seed.ts）：临时用户 e2e_s10_*、API/Key 前缀 e2e_s10_
 * - 主测试表：内置 Doris datanest.target_products（8 字段元数据）；敏感度闸门直接改库造数
 * - 串行执行（共享 module 级状态），与 api-keys.spec.ts 分开跑（各自 beforeAll/afterAll）
 */

test.describe.configure({mode: 'serial'});

const PW = 'Test123456';

// ==================== 共享状态（串行用例间传递） ====================
let sharedApiId = ''; // e2e_s10_区域统计V2（admin 建，主链路）
let engineerApiId = ''; // e2e_s10_工程师API（engineer 建）

// ==================== 小工具 ====================

/** 向导页：确保数据源=Doris 数仓、库=datanest，表区出现 target_products */
async function gotoWizardWithTable(page: Page): Promise<void> {
    await expect(page.getByLabel('数据源')).toHaveValue('-1');
    // 等库列表加载完成（加载前 select 是 disabled；option 折叠态 hidden 不能 toBeVisible）
    await expect(page.getByLabel('数据库')).toBeEnabled();
    await page.getByLabel('数据库').selectOption(TARGET.databaseName);
    await expect(page.locator('label').filter({hasText: TARGET.tableName})).toBeVisible();
}

/** 向导页：选中 target_products（点击行 label 触发 radio） */
async function pickTargetTable(page: Page): Promise<void> {
    await page.locator('label').filter({hasText: TARGET.tableName}).click();
}

/** 列表页行定位（按可见文本） */
function row(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({hasText: name});
}

/** 以指定用户走 API 创建 API（默认公开表，返回 detail） */
async function createApiViaApi(username: string, password: string, name: string, path: string) {
    const api = await Api.create();
    await api.login(username, password);
    const detail = await api.post<{ id: string }>('/data-service/apis', {
        name,
        path,
        datasourceId: TARGET.datasourceId,
        databaseName: TARGET.databaseName,
        tableName: TARGET.tableName,
        metadataTableId: getTargetTableId(),
        paginated: 1,
        pageSizeMax: 100,
    });
    await api.dispose();
    return detail;
}

// ==================== 播种/清理 ====================

test.beforeAll(async () => {
    await seedF2();
});

test.afterAll(async () => {
    await cleanupF2();
});

// ==================== A. 列表页 ====================

test('AM-1 页面加载：标题/描述/统计卡/新建按钮齐全', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');

    await expect(page.getByRole('heading', {name: 'API 管理'})).toBeVisible();
    await expect(page.getByText(/把数据表一键发布成可调用的数据 API/)).toBeVisible();

    // 统计卡 4 个（「已下线」与状态筛选 option 重名，用 role=button 精确定位统计卡）
    await expect(page.getByText('已发布 API', {exact: true})).toBeVisible();
    await expect(page.getByText('待发布 / 草稿', {exact: true})).toBeVisible();
    await expect(page.getByText('近 7 天总调用', {exact: true})).toBeVisible();
    await expect(page.getByRole('button', {name: /已下线/})).toBeVisible();
    // 新建按钮
    await expect(page.getByRole('button', {name: '新建 API'}).first()).toBeVisible();
    // 工具栏：搜索 / 状态筛选 / 我的 API
    await expect(page.getByPlaceholder('搜索 API 名称 / 路径')).toBeVisible();
    await expect(page.getByLabel('按状态筛选')).toBeVisible();
    await expect(page.getByRole('button', {name: '我的 API'})).toBeVisible();
});

test('AM-2 空态：无 API 时展示空态引导', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');

    await expect(page.getByText(/暂无数据 API/)).toBeVisible();
    // 空态 CTA 也能新建
    await expect(page.getByRole('button', {name: '新建 API'}).last()).toBeVisible();
});

// ==================== B. 创建向导 ====================

test('AM-3 向导：步骤条 + 数据源/库/表联动', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');

    // 标题 + 描述 + 返回列表
    await expect(page.getByRole('heading', {name: '新建 API'})).toBeVisible();
    await expect(page.getByRole('button', {name: '返回列表'})).toBeVisible();
    // 3 步步骤条（exact 匹配避免与描述文本/字段说明冲突）
    for (const step of ['选择数据表', '配置接口', '绑定 API Key']) {
        await expect(page.getByText(step, {exact: true})).toBeVisible();
    }
    // 数据源默认内置 Doris
    await expect(page.getByLabel('数据源')).toHaveValue('-1');
    // 库/表联动加载
    await gotoWizardWithTable(page);
    await expect(page.getByText('数据表（单选）')).toBeVisible();
});

test('AM-4 向导：选表即生成 API 预览（路径 + 暴露字段）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await gotoWizardWithTable(page);

    // 未选表时预览占位
    await expect(page.getByText(/选择左侧数据表后，这里实时预览/)).toBeVisible();

    await pickTargetTable(page);

    // 接口雏形：GET /open-api/v1/target_products（derivePathSegment 保留下划线）+ 默认 8 字段
    await expect(page.getByText('/open-api/v1/target_products')).toBeVisible();
    await expect(page.getByText('默认暴露 8 个')).toBeVisible();
    for (const col of TARGET.columns) {
        await expect(page.getByText(col, {exact: true}).first()).toBeVisible();
    }
});

test('AM-5 向导：第 2 步配置 + 前端预校验（路径非法/字段至少 1 个）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await gotoWizardWithTable(page);
    await pickTargetTable(page);
    await page.getByRole('button', {name: /下一步/}).click();

    // 名称/路径输入框
    const nameInput = page.getByPlaceholder('例如：订单区域统计');
    const pathInput = page.getByPlaceholder('orders');
    await expect(nameInput).toBeVisible();
    await expect(pathInput).toBeVisible();

    // 非法路径：前端预校验提示
    await pathInput.fill('Bad Path!');
    await page.getByRole('button', {name: /下一步/}).click();
    await expect(page.getByText(/API 路径非法/)).toBeVisible();
    await pathInput.fill('e2e-s10-region-stats');

    // 暴露字段：全选默认勾选 → 全取消 → 至少 1 个校验 → 恢复
    await expect(page.getByText('已暴露 8 / 8 个字段')).toBeVisible();
    await page.getByLabel('全选暴露字段').uncheck();
    await expect(page.getByText('已暴露 0 / 8 个字段')).toBeVisible();
    await page.getByRole('button', {name: /下一步/}).click();
    await expect(page.getByText('请至少勾选 1 个暴露字段')).toBeVisible();
    await page.getByLabel('全选暴露字段').check();
    await expect(page.getByText('已暴露 8 / 8 个字段')).toBeVisible();
});

test('AM-6 向导：参数化筛选 EQ/RANGE + 排序 + 分页配置', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await gotoWizardWithTable(page);
    await pickTargetTable(page);
    await page.getByRole('button', {name: /下一步/}).click();

    // 名称
    await page.getByPlaceholder('例如：订单区域统计').fill('e2e_s10_区域统计');

    // 筛选：name=EQ、price=RANGE
    await page.getByLabel('字段 name 的筛选方式').selectOption('EQ');
    await page.getByLabel('字段 price 的筛选方式').selectOption('RANGE');

    // 排序：stock DESC
    await page.getByLabel('排序字段').selectOption('stock');
    await page.getByLabel('排序方向').selectOption('DESC');

    // 分页：启用 + pageSize 上限 50
    await expect(page.getByText('启用分页')).toBeVisible();
    await page.getByText('pageSize 上限').locator('input').fill('50');
});

test('AM-7 向导：第 3 步绑定 Key + 完成创建跳详情', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await gotoWizardWithTable(page);
    await pickTargetTable(page);
    await page.getByRole('button', {name: /下一步/}).click();
    await page.getByPlaceholder('例如：订单区域统计').fill('e2e_s10_区域统计');
    await page.getByPlaceholder('orders').fill('e2e-s10-region-stats');
    await page.getByLabel('字段 name 的筛选方式').selectOption('EQ');
    await page.getByLabel('字段 price 的筛选方式').selectOption('RANGE');
    await page.getByLabel('排序字段').selectOption('stock');
    await page.getByLabel('排序方向').selectOption('DESC');
    await page.getByText('pageSize 上限').locator('input').fill('50');
    await page.getByRole('button', {name: /下一步/}).click();

    // 第 3 步：默认「暂不绑定」
    await expect(page.getByRole('radio', {name: /暂不绑定/})).toBeChecked();
    await expect(page.getByRole('radio', {name: /绑定已有 Key/})).toBeVisible();
    await expect(page.getByRole('radio', {name: /新建 Key/})).toBeVisible();

    await page.getByRole('button', {name: '完成创建'}).click();

    // toast + 跳详情
    await expect(page.getByText(/已创建（未发布）/)).toBeVisible();
    await expect(page).toHaveURL(/\/data-service\/api-manage\/\d+/);
    const m = page.url().match(/\/data-service\/api-manage\/(\d+)/);
    expect(m).toBeTruthy();
    sharedApiId = m![1];
    // 详情页标题 = API 名称
    await expect(page.getByRole('heading', {name: 'e2e_s10_区域统计'})).toBeVisible();
});

test('AM-7b 向导：第 3 步绑定已有 Key → 详情页显示绑定', async ({page}) => {
    // 先经 API 造一个启用态 Key（cleanupF2 按 e2e_s10_ 前缀清理）
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    await api.post('/data-service/api-keys', {name: 'e2e_s10_绑定用Key', qpsLimit: 100});
    await api.dispose();

    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await gotoWizardWithTable(page);
    await pickTargetTable(page);
    await page.getByRole('button', {name: /下一步/}).click();
    await page.getByPlaceholder('例如：订单区域统计').fill('e2e_s10_绑定已有Key的API');
    await page.getByPlaceholder('orders').fill('e2e-s10-bind-existing');
    await page.getByRole('button', {name: /下一步/}).click();

    // 第 3 步选「绑定已有 Key」→ 勾选启用态 Key
    await page.getByRole('radio', {name: /绑定已有 Key/}).check();
    await expect(page.getByText(/选择要绑定的 Key/)).toBeVisible();
    await page.locator('label').filter({hasText: 'e2e_s10_绑定用Key'}).click();
    await expect(page.getByText(/选择要绑定的 Key（1 已选）/)).toBeVisible();

    await page.getByRole('button', {name: '完成创建'}).click();
    await expect(page.getByText(/已创建（未发布）/)).toBeVisible();
    await expect(page).toHaveURL(/\/data-service\/api-manage\/\d+/);

    // 详情页：绑定 Key 列表显示该 Key
    await expect(page.getByText('绑定 Key（1）')).toBeVisible();
    await expect(page.getByText('e2e_s10_绑定用Key')).toBeVisible();
});

test('AM-7c 向导：第 3 步新建 Key → 明文一次性展示 → 详情页绑定', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
    await gotoWizardWithTable(page);
    await pickTargetTable(page);
    await page.getByRole('button', {name: /下一步/}).click();
    await page.getByPlaceholder('例如：订单区域统计').fill('e2e_s10_新建Key的API');
    await page.getByPlaceholder('orders').fill('e2e-s10-bind-new');
    await page.getByRole('button', {name: /下一步/}).click();

    // 第 3 步选「新建 Key」→ 填名称 + QPS
    await page.getByRole('radio', {name: /新建 Key/}).check();
    await page.getByPlaceholder('例如：业务-订单组').fill('e2e_s10_向导新建Key');
    await page.locator('input[type="number"]').fill('80');

    await page.getByRole('button', {name: '完成创建'}).click();

    // 明文弹窗：完整 Key 仅展示一次（K- 前缀）
    const dialog = page.getByRole('dialog', {name: 'API Key 创建成功'});
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/Key「e2e_s10_向导新建Key」已创建并绑定本 API/)).toBeVisible();
    const fullKey = (await dialog.locator('.font-mono').textContent())?.trim();
    expect(fullKey).toMatch(/^K-/);

    // 关闭弹窗 → 跳详情，绑定 Key 列表显示新 Key
    await dialog.getByRole('button', {name: '我已保存，前往 API 详情'}).click();
    await expect(page).toHaveURL(/\/data-service\/api-manage\/\d+/);
    await expect(page.getByText('绑定 Key（1）')).toBeVisible();
    await expect(page.getByText('e2e_s10_向导新建Key')).toBeVisible();
});

// ==================== C. 详情页 ====================

test('AM-8 详情页：基本信息/接口定义/调用文档/统计占位', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${sharedApiId}`);

    // 状态徽章「未发布」+ 敏感度「公开」
    await expect(page.getByText('未发布', {exact: true}).first()).toBeVisible();
    await expect(page.getByText('公开', {exact: true}).first()).toBeVisible();

    // 基本信息
    await expect(page.getByText('Doris 数仓', {exact: true})).toBeVisible();
    await expect(page.getByText('datanest.target_products')).toBeVisible();
    await expect(page.getByText('0 个', {exact: true})).toBeVisible(); // 绑定 Key 0 个
    await expect(page.getByText('近 7 天调用')).toBeVisible();

    // 接口定义：筛选 2 个（EQ + RANGE）、排序、分页、返回字段 8 个
    await expect(page.getByText('参数化筛选（2）')).toBeVisible();
    await expect(page.getByText('等值（=）')).toBeVisible();
    await expect(page.getByText('范围（min_price / max_price）')).toBeVisible();
    await expect(page.getByText('stock DESC')).toBeVisible();
    await expect(page.getByText(/分页启用（pageSize 上限 50）/)).toBeVisible();
    await expect(page.getByText('返回字段（8 个）')).toBeVisible();

    // 调用文档
    await expect(page.getByText('认证方式：')).toBeVisible();
    await expect(page.getByText(/调用示例（经网关完整路径）/)).toBeVisible();
    await expect(page.getByText('复制 curl')).toBeVisible();

    // 统计占位（F3 前）
    await expect(page.getByText(/调用统计（调用量、成功率/)).toBeVisible();
});

test('AM-9 详情页：发布成功', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${sharedApiId}`);

    await page.getByRole('button', {name: '发布'}).click();
    await expect(page.getByText('已发布，业务系统可凭绑定的 Key 调用')).toBeVisible();
    await expect(page.getByText('已发布', {exact: true}).first()).toBeVisible();
    await expect(page.getByRole('button', {name: '下线'})).toBeVisible();
});

test('AM-10 生命周期：下线 → 重新发布', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${sharedApiId}`);

    await page.getByRole('button', {name: '下线'}).click();
    await expect(page.getByText('已下线，业务系统将无法再调用该 API')).toBeVisible();
    await expect(page.getByText('已下线', {exact: true}).first()).toBeVisible();

    await page.getByRole('button', {name: '发布'}).click();
    await expect(page.getByText('已发布', {exact: true}).first()).toBeVisible();
});

test('AM-11 列表：统计卡点击下钻状态筛选（再点取消）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');

    // 当前 API-A 已发布 → 点「已发布 API」卡下钻
    await page.getByRole('button').filter({hasText: '已发布 API'}).click();
    await expect(row(page, 'e2e_s10_区域统计')).toBeVisible();
    // 表格内不应出现「未发布」徽章
    await expect(page.locator('.ant-table').getByText('未发布', {exact: true})).toHaveCount(0);

    // 再点取消下钻 → 未发布行（无）仍 0，列表恢复全部
    await page.getByRole('button').filter({hasText: '已发布 API'}).click();
    await expect(row(page, 'e2e_s10_区域统计')).toBeVisible();
});

test('AM-12 列表：搜索 / 状态筛选 / 重置', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');

    // 搜索关键词命中
    await page.getByPlaceholder('搜索 API 名称 / 路径').fill('e2e_s10_区域统计');
    await page.keyboard.press('Enter');
    await expect(row(page, 'e2e_s10_区域统计')).toBeVisible();

    // 状态筛选「未发布」→ 当前全部已发布 → 无结果
    await page.getByLabel('按状态筛选').selectOption('CREATED');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row(page, 'e2e_s10_区域统计')).toHaveCount(0);

    // 重置恢复
    await page.getByRole('button', {name: '重置'}).click();
    await expect(row(page, 'e2e_s10_区域统计')).toBeVisible();
});

// ==================== D. 编辑页 ====================

test('AM-14 编辑页：详情页「编辑」入口 + 来源只读 + 表单预填 + 保存回详情', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    // 从详情页点「编辑」按钮进入编辑页（覆盖入口跳转）
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${sharedApiId}`);
    await page.getByRole('button', {name: '编辑'}).click();
    await expect(page).toHaveURL(/\/edit$/);
    await expect(page.getByRole('heading', {name: '编辑 API'})).toBeVisible();

    // 来源只读卡：数据源名「Doris 数仓」+ 数据表名（getByText('数据源') 会子串命中侧边栏「数据源管理」，故用数据源名精确断言）
    await expect(page.getByText('Doris 数仓', {exact: true})).toBeVisible();
    await expect(page.getByText('datanest.target_products')).toBeVisible();

    // 表单预填：名称/路径/排序/分页/字段全选
    await expect(page.getByPlaceholder('例如：订单区域统计')).toHaveValue('e2e_s10_区域统计');
    await expect(page.getByPlaceholder('orders')).toHaveValue('e2e-s10-region-stats');
    await expect(page.getByLabel('排序字段')).toHaveValue('stock');
    await expect(page.getByLabel('排序方向')).toHaveValue('DESC');
    await expect(page.getByLabel('全选暴露字段')).toBeChecked();

    // 改名保存
    await page.getByPlaceholder('例如：订单区域统计').fill('e2e_s10_区域统计V2');
    await page.getByRole('button', {name: '保存'}).click();
    await expect(page.getByText('API 已保存')).toBeVisible();
    await expect(page).toHaveURL(/\/data-service\/api-manage\/\d+$/);
    await expect(page.getByRole('heading', {name: 'e2e_s10_区域统计V2'})).toBeVisible();
});

// ==================== G. 权限矩阵 ====================

test('AM-15 权限：分析师只读（无写按钮 + 写 API 403）', async ({page}) => {
    await gotoAs(page, F2_USERS.analyst.username, PW, '/data-service/api-manage');

    // 列表可见 + 无新建按钮
    await expect(page.getByRole('heading', {name: 'API 管理'})).toBeVisible();
    await expect(page.getByRole('button', {name: '新建 API'})).toHaveCount(0);
    // 目标行只有查看详情，无编辑/删除/发布（列表按 created_at DESC，首行是最新 API，不能取 firstRow）
    const targetRow = row(page, 'e2e_s10_区域统计V2');
    await expect(targetRow.getByRole('button', {name: '查看详情'})).toBeVisible();
    await expect(targetRow.getByRole('button', {name: '编辑'})).toHaveCount(0);
    await expect(targetRow.getByRole('button', {name: '删除'})).toHaveCount(0);

    // 详情页无写按钮
    await targetRow.getByRole('button', {name: '查看详情'}).click();
    await expect(page.getByRole('heading', {name: 'e2e_s10_区域统计V2'})).toBeVisible();
    await expect(page.getByRole('button', {name: '发布'})).toHaveCount(0);
    await expect(page.getByRole('button', {name: '删除'})).toHaveCount(0);

    // 直接调写 API → 403（Sa-Token 拦截：HTTP 403 + envelope code=1005「无权限访问」）
    const api = await Api.create();
    await api.login(F2_USERS.analyst.username, PW);
    const env = await api.raw('POST', '/data-service/apis', {
        name: 'e2e_s10_越权', path: 'e2e-s10-forbidden',
        datasourceId: TARGET.datasourceId, databaseName: TARGET.databaseName, tableName: TARGET.tableName, paginated: 1,
    });
    expect([1005, 403]).toContain(env.code);
    await api.dispose();
});

test('AM-16 权限：治理管理员只读（无写按钮 + 写 API 403）', async ({page}) => {
    await gotoAs(page, F2_USERS.govAdmin.username, PW, '/data-service/api-manage');

    await expect(page.getByRole('heading', {name: 'API 管理'})).toBeVisible();
    await expect(page.getByRole('button', {name: '新建 API'})).toHaveCount(0);
    // 治理员也不能改 Key（HTTP 403 + code=1005）
    const api = await Api.create();
    await api.login(F2_USERS.govAdmin.username, PW);
    const env = await api.raw('POST', '/data-service/api-keys', {name: 'e2e_s10_越权Key', qpsLimit: 5});
    expect([1005, 403]).toContain(env.code);
    await api.dispose();
});

test('AM-17 权限：工程师可写（创建 + 发布）', async ({page}) => {
    // 工程师 API 创建 + 发布
    const detail = await createApiViaApi(F2_USERS.engineer.username, PW, 'e2e_s10_工程师API', 'e2e-s10-engineer-api');
    engineerApiId = detail.id;
    const api = await Api.create();
    await api.login(F2_USERS.engineer.username, PW);
    await api.post(`/data-service/apis/${engineerApiId}/publish`);
    await api.dispose();

    // 工程师视角列表可见 + 有新建按钮（页头 + 空态 CTA 可能同时存在，取 first）
    await gotoAs(page, F2_USERS.engineer.username, PW, '/data-service/api-manage');
    await expect(page.getByRole('button', {name: '新建 API'}).first()).toBeVisible();
    await expect(row(page, 'e2e_s10_工程师API')).toBeVisible();
});

test('AM-13 列表：「我的 API / 全部」切换（按创建人过滤）', async ({page}) => {
    expect(engineerApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');

    // 全部：admin 的 + engineer 的都在
    await expect(row(page, 'e2e_s10_区域统计V2')).toBeVisible();
    await expect(row(page, 'e2e_s10_工程师API')).toBeVisible();

    // 我的 API：只剩 admin 创建的
    await page.getByRole('button', {name: '我的 API'}).click();
    await expect(row(page, 'e2e_s10_区域统计V2')).toBeVisible();
    await expect(row(page, 'e2e_s10_工程师API')).toHaveCount(0);

    // 切回全部
    await page.getByRole('button', {name: '全部', exact: true}).click();
    await expect(row(page, 'e2e_s10_工程师API')).toBeVisible();
});

// ==================== H. 敏感度闸门（直接改库造数） ====================

test('AM-18 闸门：机密表向导禁选 + API 创建 9004', async ({page}) => {
    setSensitivity('CONFIDENTIAL');
    try {
        // API 创建被拦
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const env = await api.raw('POST', '/data-service/apis', {
            name: 'e2e_s10_机密越权', path: 'e2e-s10-confidential',
            datasourceId: TARGET.datasourceId, databaseName: TARGET.databaseName, tableName: TARGET.tableName,
            metadataTableId: getTargetTableId(), paginated: 1,
        });
        expect(env.code).toBe(9004);
        await api.dispose();

        // 向导：表行 radio 禁用 + 锁图标 + 机密徽章
        await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
        await gotoWizardWithTable(page);
        const tableRow = page.locator('label').filter({hasText: TARGET.tableName});
        await expect(tableRow.locator('input[type="radio"]')).toBeDisabled();
        await expect(tableRow.getByText('机密', {exact: true})).toBeVisible();
    } finally {
        setSensitivity('PUBLIC');
    }
});

test('AM-19 闸门：内部表警告 + 默认 9004 + 超管开白放行', async ({page}) => {
    setSensitivity('INTERNAL');
    try {
        // 默认创建被拦
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        const env = await api.raw('POST', '/data-service/apis', {
            name: 'e2e_s10_内部未开白', path: 'e2e-s10-internal-blocked',
            datasourceId: TARGET.datasourceId, databaseName: TARGET.databaseName, tableName: TARGET.tableName,
            metadataTableId: getTargetTableId(), paginated: 1,
        });
        expect(env.code).toBe(9004);

        // 向导选中内部表 → 开白警告提示
        await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage/new');
        await gotoWizardWithTable(page);
        await pickTargetTable(page);
        await expect(page.getByText(/需超管在「数据分级分类」中开白/)).toBeVisible();
        await api.dispose();

        // 开白后创建成功
        setSensitivity('INTERNAL', 1);
        const created = await createApiViaApi(ADMIN.username, ADMIN.password, 'e2e_s10_内部开白API', 'e2e-s10-internal-open');
        expect(created.id).toBeTruthy();
    } finally {
        setSensitivity('PUBLIC');
    }
});

test('AM-20 闸门：已建 API 的表改机密 → 编辑/重新发布 9004（fail-closed）', async ({page}) => {
    expect(sharedApiId).toBeTruthy();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        // 先把 API-A 下线（发布幂等不会过闸门，需先下线再发布触发）
        await api.post(`/data-service/apis/${sharedApiId}/disable`);

        setSensitivity('CONFIDENTIAL');
        // 编辑被拦
        const editEnv = await api.raw('PUT', `/data-service/apis/${sharedApiId}`, {
            name: 'e2e_s10_改机密后编辑', path: 'e2e-s10-edit-after-confidential', paginated: 1,
        });
        expect(editEnv.code).toBe(9004);
        // 重新发布被拦
        const pubEnv = await api.raw('POST', `/data-service/apis/${sharedApiId}/publish`);
        expect(pubEnv.code).toBe(9004);
    } finally {
        setSensitivity('PUBLIC');
        // 恢复公开后重新发布，保持 API-A 已发布（供后续用例）
        await api.post(`/data-service/apis/${sharedApiId}/publish`);
        await api.dispose();
    }
});

// ==================== E. 生命周期（删除） ====================

test('AM-21 删除：软删后列表消失 + 详情 404 + 路径复用', async ({page}) => {
    // 新建一个专用删除 API
    const detail = await createApiViaApi(ADMIN.username, ADMIN.password, 'e2e_s10_待删除', 'e2e-s10-to-delete');

    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-manage');
    // 行内删除 → 确认弹窗
    await row(page, 'e2e_s10_待删除').getByRole('button', {name: '删除', exact: true}).click();
    await expect(page.getByRole('dialog', {name: '删除 API'})).toBeVisible();
    await expect(page.getByText(/确认删除 API「e2e_s10_待删除」/)).toBeVisible();
    await page.getByRole('dialog', {name: '删除 API'}).getByRole('button', {name: '删除'}).click();
    await expect(page.getByText(/已删除/)).toBeVisible();
    await expect(row(page, 'e2e_s10_待删除')).toHaveCount(0);

    // 详情 404
    await page.goto(`/data-service/api-manage/${detail.id}`);
    await expect(page.getByText('API 不存在或已删除')).toBeVisible();

    // 路径可复用（软删不占 path 唯一索引）
    const again = await createApiViaApi(ADMIN.username, ADMIN.password, 'e2e_s10_待删除2', 'e2e-s10-to-delete');
    expect(again.id).toBeTruthy();
});

// ==================== 边界：未登录 ====================

test('AM-22 未登录访问跳转登录页', async ({page}) => {
    await page.goto('/data-service/api-manage');
    await expect(page).toHaveURL(/\/login/);
});
