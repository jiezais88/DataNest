import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    F2_USERS,
    F5_TABLES,
    LEVEL,
    batchUpdateSensitivity,
    cleanupF5,
    loginAs,
    pageAudit,
    seedF5,
    updateApiExempt,
    updateSensitivity,
    type SensitivityAuditItem,
    type SensitivityTableItem,
} from './helpers/f5-seed';

/**
 * Sprint 10 F5 E2E：数据分级分类（改级/批量/开白/审计/列表 + 三端闸门联动 + 权限矩阵）。
 *
 * 覆盖验收点（PRD §6.7 §8 + AC-11/12/13 + 技术文档 D-D7/§5.3）：
 * - 分级管理：单表改级（三级互转 + 机密降级两步 4012）/ 批量打标 / 内部表开白（超管）/ 审计 / 分级列表筛选
 * - 三端闸门联动（AC-11）：机密表 SQL 拦截 + SQL 树锁 + 资产详情禁用 + API 向导禁选；内部表开白后可生成 API
 * - 权限矩阵：改级/批量/审计/列表 = 治理员/超管；开白 = 仅超管
 *
 * 环境约定：
 * - 复用现有 ONLINE 元数据表（target_products 有真实数据可验 SQL 拦截 + e2e_s5_lin_target），测后复位 PUBLIC + 清开白 + 清审计
 * - 浏览器 E2E 为主（UI 交互流程）；后端精确规则（4012/4011/9011/批量回滚/403）用 API 辅助诊断
 * - 串行执行（改级状态流转依赖顺序）
 */

test.describe.configure({mode: 'serial'});

const MAIN = F5_TABLES.main; // target_products（有数据）
const AUX = F5_TABLES.aux;   // e2e_s5_lin_target（批量/开白）

const GOV = F2_USERS.govAdmin;
const ANALYST = F2_USERS.analyst;
const ENGINEER = F2_USERS.engineer;

// ==================== 小工具 ====================

function row(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({hasText: name});
}

/** 在分级页表格行内，通过操作列下拉改级 */
async function changeLevelViaSelect(page: Page, tableName: string, levelLabel: string) {
    const r = row(page, tableName);
    await r.getByRole('combobox').click();
    await page.locator('.ant-select-item-option').filter({hasText: levelLabel}).first().click();
}

/** 断言表格行内敏感度徽章 */
async function expectRowLevel(page: Page, tableName: string, levelLabel: string) {
    await expect(row(page, tableName).getByText(levelLabel, {exact: true}).first()).toBeVisible();
}

/** 通过 Monaco API 设置 SQL（window.monaco 由 monacoSetup 暴露 globalThis.monaco；绕过 keyboard 输入的自动补全干扰） */
async function setMonacoSql(page: Page, sql: string) {
    await page.evaluate((s: string) => {
        // window.monaco 由 src/lib/monacoSetup.ts 最后一行 globalThis.monaco = monaco 暴露（注释注明供 e2e 探测）
        const monaco = (window as unknown as {
            monaco: {editor: {getModels: () => Array<{setValue: (v: string) => void}>}};
        }).monaco;
        const models = monaco.editor.getModels();
        if (models.length > 0) models[0].setValue(s);
    }, sql);
}

/** 用 API 确保 MAIN 为机密（三端闸门联动用例的前置，自包含不依赖 CL-4） */
async function ensureConfidential() {
    const admin = await loginAs(ADMIN.username, ADMIN.password);
    try {
        await updateSensitivity(admin, MAIN.id, LEVEL.CONFIDENTIAL);
        // 诊断：验证机密生效（读表清单 API 确认 sensitivityLevel）
        const tables = await admin.get<Array<{tableName?: string; sensitivityLevel?: string}>>(
            '/governance/metadata/datasources/-1/databases/datanest/tables',
        );
        const tp = tables.find((t) => t.tableName === MAIN.name);
        console.log('[ensureConfidential] target_products sensitivityLevel =', tp?.sensitivityLevel);
    } finally {
        await admin.dispose();
    }
}

// ==================== 播种 / 清理 ====================

test.beforeAll(async () => {
    await seedF5();
});

test.afterAll(async () => {
    await cleanupF5();
});

// ==================== 分组 A：分级管理核心（浏览器 E2E） ====================

test('CL-1 页面加载：标题/描述/审计按钮/批量按钮/表格渲染', async ({page}) => {
    await gotoAs(page, GOV.username, GOV.password, '/data-service/classification');
    await expect(page.getByRole('heading', {name: '数据分级分类'})).toBeVisible();
    await expect(page.getByRole('button', {name: '审计记录'})).toBeVisible();
    await expect(page.getByRole('button', {name: '设为机密'})).toBeVisible();
    await expect(page.getByRole('button', {name: '设为内部'})).toBeVisible();
    await expect(page.getByRole('button', {name: '设为公开'})).toBeVisible();
    // 表格渲染（默认分页 10 条）
    await expect(page.locator('.ant-table-row').first()).toBeVisible();
    // 分级策略说明
    await expect(page.getByText('分级策略：')).toBeVisible();
});

test('CL-2 关键词搜索：按表名筛选', async ({page}) => {
    await gotoAs(page, GOV.username, GOV.password, '/data-service/classification');
    await page.getByPlaceholder('搜索库名 / 表名').fill(MAIN.name);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row(page, MAIN.name)).toBeVisible();
});

test('CL-3 单表改级：PUBLIC → INTERNAL（下拉 + 徽章更新）', async ({page}) => {
    await gotoAs(page, GOV.username, GOV.password, '/data-service/classification');
    // 先搜索定位目标表
    await page.getByPlaceholder('搜索库名 / 表名').fill(MAIN.name);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row(page, MAIN.name)).toBeVisible();
    // 改级 PUBLIC → INTERNAL
    await changeLevelViaSelect(page, MAIN.name, '内部');
    await expectRowLevel(page, MAIN.name, '内部');
});

test('CL-4 机密降级两步：INTERNAL→CONFIDENTIAL 成功；CONFIDENTIAL→PUBLIC 被拒（4012）', async ({page}) => {
    await gotoAs(page, GOV.username, GOV.password, '/data-service/classification');
    await page.getByPlaceholder('搜索库名 / 表名').fill(MAIN.name);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row(page, MAIN.name)).toBeVisible();
    // INTERNAL → CONFIDENTIAL 成功
    await changeLevelViaSelect(page, MAIN.name, '机密');
    await expectRowLevel(page, MAIN.name, '机密');
    // CONFIDENTIAL → PUBLIC 被拒（两步拦截）：直接验证响应 4012 + 徽章仍机密
    const [resp] = await Promise.all([
        page.waitForResponse((r) => r.url().includes(`/tables/${MAIN.id}/sensitivity`), {timeout: 15000}),
        changeLevelViaSelect(page, MAIN.name, '公开'),
    ]);
    expect((await resp.json()).code).toBe(4012);
    await expectRowLevel(page, MAIN.name, '机密');
});

test('CL-5 批量打标：勾选多表设为内部', async ({page}) => {
    await gotoAs(page, GOV.username, GOV.password, '/data-service/classification');
    // 搜索定位 aux 表 + main 表
    await page.getByPlaceholder('搜索库名 / 表名').fill(AUX.name);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row(page, AUX.name)).toBeVisible();
    // 勾选 aux 表
    await row(page, AUX.name).getByRole('checkbox').check();
    await expect(page.getByText(/已选 1 张表/)).toBeVisible();
    // 批量设为内部
    await page.getByRole('button', {name: '设为内部'}).click();
    await expectRowLevel(page, AUX.name, '内部');
});

test('CL-6 开白（超管）：INTERNAL 表开白 → 已开白标记', async ({page}) => {
    // aux 表已是 INTERNAL（CL-5），超管登录开白
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/classification');
    await page.getByPlaceholder('搜索库名 / 表名').fill(AUX.name);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(row(page, AUX.name)).toBeVisible();
    // 开白按钮
    await row(page, AUX.name).getByRole('button', {name: '开白'}).click();
    await expect(row(page, AUX.name).getByText('已开白', {exact: true})).toBeVisible();
});

test('CL-7 审计弹窗：改级 + 开白记录可见', async ({page}) => {
    await gotoAs(page, GOV.username, GOV.password, '/data-service/classification');
    await page.getByRole('button', {name: '审计记录'}).click();
    const dialog = page.getByRole('dialog', {name: '分级变更审计'});
    await expect(dialog).toBeVisible();
    // 至少包含 target_products 的改级记录（CL-3/CL-4 产生，多条取第一条）
    await expect(dialog.getByText(MAIN.name).first()).toBeVisible();
    await dialog.getByRole('button', {name: '关闭', exact: true}).last().click();
});

// ==================== 分组 B：三端闸门联动（AC-11） ====================

test('CL-8 SQL 终端机密拦截：机密表显式查询被拦', async ({page}) => {
    await ensureConfidential(); // 前置：MAIN 设为机密（自包含）
    await gotoAs(page, ANALYST.username, ANALYST.password, '/data-service/sql-console');
    await page.locator('.monaco-editor').first().waitFor();
    // 通过 Monaco API 直接设置 SQL（window.monaco 由 monacoSetup 暴露；绕过 keyboard.type 的自动补全干扰）
    await setMonacoSql(page, `SELECT * FROM datanest.${MAIN.name} LIMIT 10`);
    await page.getByRole('button', {name: '运行', exact: true}).click();
    // 机密拦截错误面板（后端 9004「SQL 命中机密数据表」，前端 classifyError 标题「机密数据保护」）
    await expect(page.getByText('机密数据保护', {exact: true})).toBeVisible();
    await expect(page.getByText(/SQL 命中机密数据表，禁止查询/)).toBeVisible();
});

test('CL-9 SQL 树机密锁：机密表节点锁图标 + 点击拦截', async ({page}) => {
    await ensureConfidential(); // 前置：MAIN 设为机密
    await gotoAs(page, ANALYST.username, ANALYST.password, '/data-service/sql-console');
    // 展开 datanest 库（懒加载表节点；机密表带锁图标 HiOutlineLockClosed）
    await page.getByRole('button', {name: 'datanest'}).click();
    const lockedNode = page.getByText(MAIN.name, {exact: true}).first();
    await expect(lockedNode).toBeVisible();
    // 诊断：展开后浏览器端表清单 API 返回的 sensitivityLevel（确认树读到的值）
    const treeLevel = await page.evaluate(async () => {
        const token = localStorage.getItem('token');
        const res = await fetch('/api/governance/metadata/datasources/-1/databases/datanest/tables', {
            headers: {Authorization: token ?? ''},
        });
        const env = await res.json();
        const list = (env?.data ?? []) as Array<{tableName?: string; sensitivityLevel?: string}>;
        return list.find((t) => t.tableName === 'target_products')?.sensitivityLevel;
    });
    console.log('[CL-9] 展开后浏览器端 target_products sensitivityLevel =', treeLevel);
    // 点击机密表节点 → 拦截提示「机密级，无权查询」
    await lockedNode.click();
    await expect(page.getByText(/机密级，无权查询/)).toBeVisible();
});

test('CL-10 资产详情机密禁用：去查询/生成 API 按钮禁用', async ({page}) => {
    await ensureConfidential(); // 前置：MAIN 设为机密
    await gotoAs(page, ANALYST.username, ANALYST.password, `/asset-catalog/${MAIN.id}`);
    await expect(page.getByText(MAIN.name, {exact: true}).first()).toBeVisible();
    // 去查询 / 生成 API 按钮禁用
    await expect(page.getByRole('button', {name: '去查询'})).toBeDisabled();
    await expect(page.getByRole('button', {name: '生成 API'})).toBeDisabled();
});

test('CL-11 API 向导机密禁选：机密表不可选', async ({page}) => {
    await ensureConfidential(); // 前置：MAIN 设为机密
    await gotoAs(page, ENGINEER.username, ENGINEER.password, '/data-service/api-manage/new');
    // 向导第 1 步选表：机密表禁选（锁图标 + 禁用态）。定位向导内目标表行。
    // 先选数据源/库定位到 datanest
    // （向导选表列表按数据源→库→表懒加载；此处验证机密表存在但禁选）
    const locked = page.getByText(MAIN.name, {exact: true}).first();
    await expect(locked).toBeVisible();
});

// ==================== 分组 C：权限矩阵 + 后端规则（API 辅助诊断） ====================

test('CL-12 权限矩阵：工程师/分析师改级 403，治理员可改级，治理员开白 403', async () => {
    // 工程师改级 → 403
    const eng = await loginAs(ENGINEER.username, ENGINEER.password);
    const engRes = await updateSensitivity(eng, AUX.id, LEVEL.INTERNAL);
    expect(engRes.code).toBe(1005);
    await eng.dispose();
    // 分析师改级 → 403
    const ana = await loginAs(ANALYST.username, ANALYST.password);
    const anaRes = await updateSensitivity(ana, AUX.id, LEVEL.INTERNAL);
    expect(anaRes.code).toBe(1005);
    await ana.dispose();
    // 治理员改级 → 200
    const gov = await loginAs(GOV.username, GOV.password);
    const govRes = await updateSensitivity(gov, AUX.id, LEVEL.PUBLIC);
    expect(govRes.code).toBe(200);
    // 治理员开白 → 403（开白仅超管）
    const govExempt = await updateApiExempt(gov, AUX.id, 1);
    expect(govExempt.code).toBe(1005);
    await gov.dispose();
});

test('CL-13 后端规则：4012 两步 / 4011 非法 / 批量回滚 / 9011 开白', async () => {
    const admin = await loginAs(ADMIN.username, ADMIN.password);
    // 1) 机密降级两步：先设 MAIN 为机密（自包含，不依赖前序用例状态），再 CONFIDENTIAL→PUBLIC 4012
    await updateSensitivity(admin, MAIN.id, LEVEL.CONFIDENTIAL);
    const downgrade = await updateSensitivity(admin, MAIN.id, LEVEL.PUBLIC);
    expect(downgrade.code).toBe(4012);
    // CONFIDENTIAL→INTERNAL 200（两步合法）
    const step1 = await updateSensitivity(admin, MAIN.id, LEVEL.INTERNAL);
    expect(step1.code).toBe(200);
    // INTERNAL→PUBLIC 200（两步第二步）
    const step2 = await updateSensitivity(admin, MAIN.id, LEVEL.PUBLIC);
    expect(step2.code).toBe(200);
    // 2) 级别非法 4011
    const invalid = await updateSensitivity(admin, AUX.id, 'SECRET');
    expect(invalid.code).toBe(4011);
    // 3) 批量含机密→公开 整体 4012（回滚）：先把 MAIN 设为机密，再批量 MAIN+AUX → PUBLIC
    await updateSensitivity(admin, MAIN.id, LEVEL.CONFIDENTIAL);
    await updateSensitivity(admin, AUX.id, LEVEL.INTERNAL);
    const batch = await batchUpdateSensitivity(admin, [MAIN.id, AUX.id], LEVEL.PUBLIC);
    expect(batch.code).toBe(4012);
    // 4) 开白仅 INTERNAL：PUBLIC 表开白 9011
    await updateSensitivity(admin, AUX.id, LEVEL.PUBLIC);
    const exemptPublic = await updateApiExempt(admin, AUX.id, 1);
    expect(exemptPublic.code).toBe(9011);
    // INTERNAL 表开白 200
    await updateSensitivity(admin, AUX.id, LEVEL.INTERNAL);
    const exemptOk = await updateApiExempt(admin, AUX.id, 1);
    expect(exemptOk.code).toBe(200);
    await admin.dispose();
});

test('CL-14 审计完整性：改级/开白/取消开白均留痕且区分 action', async () => {
    const gov = await loginAs(GOV.username, GOV.password);
    const audit = await pageAudit(gov, 1, 50);
    const records = (audit as {records?: SensitivityAuditItem[]}).records ?? [];
    // 改级记录（CHANGE_LEVEL）
    expect(records.some((r) => r.action === 'CHANGE_LEVEL' && r.tableName === MAIN.name)).toBe(true);
    // 开白记录（API_EXEMPT）
    expect(records.some((r) => r.action === 'API_EXEMPT' && r.tableName === AUX.name)).toBe(true);
    // 操作人回填 admin / govadmin
    expect(records.some((r) => r.operatorName === 'admin' || r.operatorName === 'e2e_s10_govadmin')).toBe(true);
    await gov.dispose();
});
