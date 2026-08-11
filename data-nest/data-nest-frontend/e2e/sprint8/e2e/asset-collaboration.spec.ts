import {expect, type Page, test} from '@playwright/test';
import {API_BASE, Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {psqlEng, psqlGov, scalarGov} from '../../sprint7/helpers/db';
import {parseXlsxRows, xlsxText} from '../helpers/xlsx';
import {seedAll} from '../../sprint7/helpers/seed';
import {ADMIN, DS_ID, T1_ID, T1_NAME, T2_ID, T2_NAME, TEST_USERS} from '../../sprint7/helpers/data';

/**
 * Sprint 8 F1 资产目录深化 E2E 测试（DC-06~09 业务主链路全覆盖，API 辅助诊断）。
 *
 * 覆盖：DC-06 数据标签（打/删/幂等/标签云/标签筛选/搜索标签维度/详情页 chip 跳转）、
 * DC-07 收藏与关注（切换幂等/我的收藏/我的关注/变更动态/筛选/导出 CSV）、
 * DC-08 评论（发表/列表/作者与治理员删除/他人无权 4023）、
 * DC-09 热度（埋点/热门面板/sort=hot/sort=latest/viewCount 全场景回填）。
 *
 * 测试数据：复用 sprint7 seedAll 的 e2e_s7 表（T1/T2）与测试用户（s7_*）；
 * 协作数据（标签/收藏/关注/评论/热度）由本 spec 自行播种并在 afterAll 清理（e2e_s8 前缀）。
 */

let admin: Api;
let gov: Api;
let engineer: Api;
let analyst: Api;

const TAG_CORE = 'e2e_s8_核心表';
const TAG_EXTRA = 'e2e_s8_高可用';
const COMMENT_TEXT = 'e2e_s8 评论：每日 00:30 更新，可放心使用';
const CHANGE_COLUMN = 'e2e_s8_amount_tax';

/** 清空 T1/T2 上的全部协作数据 + e2e_s8 前缀标签（幂等，beforeAll/afterAll 都用） */
function cleanCollaboration(): void {
    psqlGov(`DELETE FROM asset_table_tag WHERE table_id IN (${T1_ID}, ${T2_ID})`);
    psqlGov(`DELETE FROM asset_tag WHERE name LIKE 'e2e_s8%'`);
    psqlGov(`DELETE FROM asset_favorite WHERE table_id IN (${T1_ID}, ${T2_ID})`);
    psqlGov(`DELETE FROM asset_follow WHERE table_id IN (${T1_ID}, ${T2_ID})`);
    psqlGov(`DELETE FROM asset_comment WHERE table_id IN (${T1_ID}, ${T2_ID})`);
    psqlGov(`DELETE FROM asset_view_log WHERE table_id IN (${T1_ID}, ${T2_ID})`);
    psqlGov(`DELETE FROM collect_change_detail WHERE table_name IN ('${T1_NAME}', '${T2_NAME}') AND column_name LIKE 'e2e_s8%'`);
}

/** 表格行定位（按表名） */
function assetRow(page: Page, tableName: string) {
    return page.locator('.ant-table-row').filter({hasText: tableName});
}

/** antd 通知断言 */
function notice(page: Page, text: string | RegExp) {
    return page.locator('.ant-message-notice').filter({hasText: text}).first();
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    admin = await Api.create();
    await admin.login(ADMIN.username, ADMIN.password);
    // 复用 sprint7 播种（幂等），本 spec 支持 SKIP_SETUP=1 独立运行时同样自带播种
    await seedAll();
    cleanCollaboration();
    gov = await Api.create();
    await gov.login(TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password);
    engineer = await Api.create();
    await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
    analyst = await Api.create();
    await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
});

test.afterAll(async () => {
    cleanCollaboration();
    await admin?.dispose();
    await gov?.dispose();
    await engineer?.dispose();
    await analyst?.dispose();
});

// ==================== DC-06 数据标签 ====================

test.describe('DC-06 数据标签', () => {
    test('详情页打标签/删标签（全角色可用）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);

        // 初始无标签
        await expect(page.getByText('暂无标签')).toBeVisible();

        // 打标签：输入即建，回车提交
        await page.getByRole('button', {name: /添加标签/}).click();
        await page.getByPlaceholder('输入标签名，回车创建/复用').fill(TAG_CORE);
        await page.getByPlaceholder('输入标签名，回车创建/复用').press('Enter');
        await expect(page.getByRole('button', {name: TAG_CORE, exact: true})).toBeVisible();

        // 再打第二个标签
        await page.getByRole('button', {name: /添加标签/}).click();
        await page.getByPlaceholder('输入标签名，回车创建/复用').fill(TAG_EXTRA);
        await page.getByPlaceholder('输入标签名，回车创建/复用').press('Enter');
        await expect(page.getByRole('button', {name: TAG_EXTRA, exact: true})).toBeVisible();

        // 删除第二个标签（hover 出现 ×）
        await page.getByRole('button', {name: TAG_EXTRA, exact: true}).hover();
        await page.getByRole('button', {name: `移除标签 ${TAG_EXTRA}`}).click();
        await expect(notice(page, `已移除标签「${TAG_EXTRA}」`)).toBeVisible();
        await expect(page.getByRole('button', {name: TAG_EXTRA, exact: true})).toHaveCount(0);
        // 孤儿标签物理删除（无任何表引用）
        expect(scalarGov(`SELECT COUNT(*) FROM asset_tag WHERE name = '${TAG_EXTRA}'`)).toBe('0');
    });

    test('打标签幂等 + 标签字典复用（API）', async () => {
        // 同一表重复打同名标签 → 仍只有 1 条绑定
        await analyst.post(`/governance/assets/tables/${T2_ID}/tags`, {tagName: TAG_CORE});
        await analyst.post(`/governance/assets/tables/${T2_ID}/tags`, {tagName: TAG_CORE});
        const tags = await analyst.get<any[]>(`/governance/assets/tables/${T2_ID}/tags`);
        expect(tags.filter(t => t.tagName === TAG_CORE).length).toBe(1);
        // 同名标签复用同一字典行（TAG_CORE 在 UI 测试已建）
        expect(scalarGov(`SELECT COUNT(*) FROM asset_tag WHERE name = '${TAG_CORE}'`)).toBe('1');
        // 标签云：TAG_CORE 绑定 2 张表（T1 UI 测试已打 + T2）
        const cloud = await analyst.get<any[]>('/governance/assets/tags');
        const core = cloud.find(t => t.tagName === TAG_CORE);
        expect(Number(core.refCount)).toBe(2);
        // 空标签名 → 4024
        const invalid = await analyst.raw('POST', `/governance/assets/tables/${T2_ID}/tags`, {tagName: '  '});
        expect(invalid.code).toBe(4024);
    });

    test('资产首页标签云筛选 + 标签列展示 + browse tag 参数', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        // 标签云行含 e2e_s8 芯片（绑定数悬浮提示）
        await expect(page.getByText('标签筛选', {exact: true})).toBeVisible();
        await page.getByRole('button', {name: TAG_CORE, exact: true}).click();
        // 命中 T1/T2（两张表都打了 TAG_CORE）
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        // 标签列展示芯片
        await expect(assetRow(page, T1_NAME).getByText(TAG_CORE, {exact: true})).toBeVisible();
        // 点「全部」取消筛选
        await page.getByRole('button', {name: '全部', exact: true}).click();
        // browse tag 参数（API 辅助）
        const data = await analyst.get<{ records: any[]; total: number }>(
            `/governance/assets/browse?tag=${encodeURIComponent(TAG_CORE)}&pageSize=50`);
        expect(data.records.map(r => r.tableName).sort()).toEqual([T1_NAME, T2_NAME].sort());
        for (const r of data.records) expect(r.tags).toContain(TAG_CORE);
    });

    test('搜索关键词命中标签名（score=40）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await page.getByLabel('搜索数据资产').fill('e2e_s8_核心');
        await page.getByRole('button', {name: /查询/}).click();
        // 标签名模糊命中 → T1/T2 出现在搜索结果
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        const hits = await analyst.get<any[]>(`/governance/assets/search?keyword=${encodeURIComponent('e2e_s8_核心')}`);
        const byName = Object.fromEntries(hits.map(r => [r.tableName, r.score]));
        expect(byName[T1_NAME]).toBe(40);
        expect(byName[T2_NAME]).toBe(40);
    });

    test('详情页标签 chip 点击 → 跳转资产目录按标签筛选', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);
        await page.getByRole('button', {name: TAG_CORE, exact: true}).click();
        // ?tag= 是一次性消费（AssetsPage 读到即清 URL），不等 tag URL，直接断言落在资产目录且筛选生效
        await page.waitForURL('**/asset-catalog');
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        // 标签云 chip 处于选中态
        await expect(page.getByRole('button', {name: TAG_CORE, exact: true})).toHaveClass(/bg-ds-accent text-white/);
    });
});

// ==================== DC-07 收藏与关注 ====================

test.describe('DC-07 收藏与关注', () => {
    test('详情页收藏/关注切换（幂等）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);
        // 收藏
        await page.getByRole('button', {name: '收藏', exact: true}).click();
        await expect(notice(page, '已收藏')).toBeVisible();
        await expect(page.getByRole('button', {name: '已收藏', exact: true})).toBeVisible();
        // 关注
        await page.getByRole('button', {name: '关注', exact: true}).click();
        await expect(notice(page, /已关注/)).toBeVisible();
        await expect(page.getByRole('button', {name: '已关注', exact: true})).toBeVisible();
        // 重复收藏/关注（API 幂等，不报错不重复）
        await analyst.post(`/governance/assets/tables/${T1_ID}/favorite`);
        await analyst.post(`/governance/assets/tables/${T1_ID}/follow`);
        expect(scalarGov(
            `SELECT COUNT(*) FROM asset_favorite WHERE table_id = ${T1_ID}`)).toBe('1');
        expect(scalarGov(
            `SELECT COUNT(*) FROM asset_follow WHERE table_id = ${T1_ID}`)).toBe('1');
    });

    test('我的收藏页：列表/筛选/取消收藏/菜单入口', async ({page}) => {
        // 再收藏 T2（T1 上个测试已收藏）
        await analyst.post(`/governance/assets/tables/${T2_ID}/favorite`);
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog/favorites');
        await expect(page.getByRole('heading', {name: '我的收藏'})).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        // 收藏时间列（行内有收藏时间/最近更新两个日期，取第一个即可）
        await expect(assetRow(page, T1_NAME).getByText(/\d{4}-\d{2}-\d{2}/).first()).toBeVisible();

        // 关键词筛选：仅 T1
        await page.getByLabel('搜索我的收藏').fill(T1_NAME);
        await page.getByRole('button', {name: /查询/}).click();
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
        await page.getByRole('button', {name: '重置'}).click();
        await expect(assetRow(page, T2_NAME)).toBeVisible();

        // 健康度筛选：T1 评分 95 EXCELLENT → 命中；T2 85 GOOD → 滤掉
        await page.getByLabel('按健康度筛选').selectOption('EXCELLENT');
        await page.getByRole('button', {name: /查询/}).click();
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
        await page.getByRole('button', {name: '重置'}).click();

        // 取消收藏 T2
        await assetRow(page, T2_NAME).getByRole('button', {name: '取消收藏'}).click();
        await page.getByRole('dialog', {name: '取消收藏'}).getByRole('button', {name: '取消收藏'}).click();
        await expect(notice(page, `已取消收藏「${T2_NAME}」`)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
        await expect(assetRow(page, T1_NAME)).toBeVisible();
    });

    test('我的收藏 API：筛选参数 + viewCount 回填 + 导出 CSV', async () => {
        // 埋点 2 次（当天 upsert 累加）→ viewCount=2
        await analyst.post(`/governance/assets/tables/${T1_ID}/view`);
        await analyst.post(`/governance/assets/tables/${T1_ID}/view`);
        const fav = await analyst.get<{ records: any[]; total: number }>('/governance/assets/my-favorites?pageSize=50');
        const t1 = fav.records.find(r => r.tableName === T1_NAME);
        expect(Number(t1.viewCount)).toBeGreaterThanOrEqual(2);
        // keyword / healthLevel / datasourceId 筛选
        const byKw = await analyst.get<{ total: number }>(
            `/governance/assets/my-favorites?keyword=${encodeURIComponent(T1_NAME)}`);
        expect(Number(byKw.total)).toBe(1);
        const byHealthMiss = await analyst.get<{ total: number }>(
            '/governance/assets/my-favorites?healthLevel=BAD');
        expect(Number(byHealthMiss.total)).toBe(0);
        const byDs = await analyst.get<{ total: number }>(
            `/governance/assets/my-favorites?datasourceId=${DS_ID}`);
        expect(Number(byDs.total)).toBe(1);
        // 导出 xlsx（表头 + 数据行 + 时间格式）
        const res = await analyst.ctx.fetch(`${API_BASE}/governance/assets/my-favorites/export`, {
            headers: {Authorization: analyst.token!},
        });
        expect(res.status()).toBe(200);
        expect(res.headers()['content-type']).toContain('spreadsheetml');
        const body = xlsxText(parseXlsxRows(await res.body()));
        expect(body).toContain('表名,注释,数据源');
        // 时间格式约定（2026-08-11 用户确认）：yyyy-MM-dd HH:mm:ss，禁止 ISO 带 T
        expect(body).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
        expect(body).not.toMatch(/\d{4}-\d{2}-\d{2}T\d{2}/);
        expect(body).toContain(T1_NAME);
    });

    test('我的关注页：变更动态 + 取消关注', async ({page}) => {
        // 造一条 T1 的采集变更明细（ADDED_COLUMN）
        psqlGov(`INSERT INTO collect_change_detail (id, history_id, change_type, database_name, schema_name, table_name, column_name, new_value, created_at)
                 VALUES (9000080000000000001, 0, 'ADDED_COLUMN', 'testdb', NULL, '${T1_NAME}', '${CHANGE_COLUMN}', 'DECIMAL(18,2)|true|含税金额', NOW())`);
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog/follows');
        await expect(page.getByRole('heading', {name: '我的关注'})).toBeVisible();
        // T1 上个测试已关注：行 + 变更动态摘要
        const row = assetRow(page, T1_NAME);
        await expect(row).toBeVisible();
        await expect(row.getByText(`元数据变更：新增字段 ${CHANGE_COLUMN}`)).toBeVisible();

        // 取消关注 → 行消失
        await row.getByRole('button', {name: '取消关注'}).click();
        await page.getByRole('dialog', {name: '取消关注'}).getByRole('button', {name: '取消关注'}).click();
        await expect(notice(page, `已取消关注「${T1_NAME}」`)).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toHaveCount(0);
        await expect(page.getByText('暂无关注，去资产详情页点击「关注」吧')).toBeVisible();
    });
});

// ==================== DC-08 评论与讨论 ====================

test.describe('DC-08 评论与讨论', () => {
    test('发表评论 → 列表展示 → 页签计数 → 作者删除', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);
        await page.getByRole('tab', {name: /评论/}).click();
        await expect(page.getByText('暂无评论，来发表第一条看法吧')).toBeVisible();

        await page.getByPlaceholder('写下你对这张表的看法，帮助同事更好地使用…').fill(COMMENT_TEXT);
        await page.getByRole('button', {name: '发表', exact: true}).click();
        await expect(notice(page, '评论已发表')).toBeVisible();
        // 列表展示：内容 + 用户名 + 时间（用户名在头部/基础信息页签也会出现，限定评论面板作用域）
        await expect(page.getByText(COMMENT_TEXT)).toBeVisible();
        await expect(page.getByLabel('评论').getByText(TEST_USERS.analyst.username, {exact: true})).toBeVisible();
        // 页签计数 = 1
        await expect(page.getByRole('tab', {name: /评论/})).toContainText('1');

        // 作者删除自己的评论
        await page.getByRole('button', {name: '删除', exact: true}).click();
        await page.getByRole('dialog', {name: '删除评论'}).getByRole('button', {name: '删除'}).click();
        await expect(notice(page, '评论已删除')).toBeVisible();
        await expect(page.getByText(COMMENT_TEXT)).toHaveCount(0);
        // 软删：deleted=1 留存
        expect(scalarGov(`SELECT deleted FROM asset_comment WHERE table_id = ${T1_ID} ORDER BY id DESC LIMIT 1`)).toBe('1');
    });

    test('他人无权删除（UI 隐藏 + API 4023）；治理员可删', async ({page}) => {
        // analyst 经 API 留一条评论
        await analyst.post(`/governance/assets/tables/${T1_ID}/comments`, {content: COMMENT_TEXT});

        // 工程师视角：看得到评论，无删除按钮
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, `/asset-catalog/${T1_ID}?tab=comments`);
        await expect(page.getByText(COMMENT_TEXT)).toBeVisible();
        await expect(page.getByRole('button', {name: '删除', exact: true})).toHaveCount(0);

        // 工程师 API 强删 → 4023
        const commentId = scalarGov(`SELECT id FROM asset_comment WHERE table_id = ${T1_ID} AND deleted = 0 ORDER BY id DESC LIMIT 1`);
        const forbidden = await engineer.raw('DELETE', `/governance/assets/comments/${commentId}`);
        expect(forbidden.code).toBe(4023);

        // 治理员视角：可删任意评论
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, `/asset-catalog/${T1_ID}?tab=comments`);
        await expect(page.getByText(COMMENT_TEXT)).toBeVisible();
        await page.getByRole('button', {name: '删除', exact: true}).click();
        await page.getByRole('dialog', {name: '删除评论'}).getByRole('button', {name: '删除'}).click();
        await expect(notice(page, '评论已删除')).toBeVisible();
        await expect(page.getByText(COMMENT_TEXT)).toHaveCount(0);
    });
});

// ==================== DC-09 热度排行 ====================

test.describe('DC-09 热度排行', () => {
    test('详情页热度指标卡 + 会话级埋点去重', async ({page}) => {
        const before = Number(scalarGov(
            `SELECT COALESCE(SUM(view_count), 0) FROM asset_view_log WHERE table_id = ${T2_ID}`));
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T2_ID}`);
        // 热度指标卡（近 30 天访问）
        await expect(page.getByText('热度（近 30 天访问）', {exact: true})).toBeVisible();
        // 首次打开上报 +1
        await expect.poll(() => Number(scalarGov(
            `SELECT COALESCE(SUM(view_count), 0) FROM asset_view_log WHERE table_id = ${T2_ID}`))).toBe(before + 1);
        // 同会话刷新页面 → sessionStorage 去重，不再上报
        await page.reload();
        await page.waitForTimeout(1000);
        expect(Number(scalarGov(
            `SELECT COALESCE(SUM(view_count), 0) FROM asset_view_log WHERE table_id = ${T2_ID}`))).toBe(before + 1);
    });

    test('资产首页热门面板 + sort=hot 排序', async ({page}) => {
        // T1 已有 2 次访问（DC-07 测试埋点），T2 有 1 次 → TAG_CORE 筛选下 T1 热度更高
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        // 热门数据表面板（近 30 天）
        await expect(page.getByText('热门数据表', {exact: true})).toBeVisible();
        await expect(page.getByText('按详情页访问埋点聚合')).toBeVisible();

        // 标签筛选 TAG_CORE（T1/T2）→ 排序按热度 → 首行 T1
        await page.getByRole('button', {name: TAG_CORE, exact: true}).click();
        await page.getByLabel('排序方式').selectOption('hot');
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        const firstRowText = await page.locator('.ant-table-row').first().innerText();
        expect(firstRowText).toContain(T1_NAME);
        // 热度列有火焰数值
        await expect(assetRow(page, T1_NAME).getByText(/\d+/).first()).toBeVisible();

        // sort=latest（API 辅助：updatedAt 降序）
        const latest = await analyst.get<{ records: any[] }>(
            `/governance/assets/browse?tag=${encodeURIComponent(TAG_CORE)}&sort=latest&pageSize=50`);
        expect(latest.records.length).toBe(2);
        const times = latest.records.map(r => Date.parse(r.updatedAt));
        expect(times[0]).toBeGreaterThanOrEqual(times[1]);
        // hot-tables API（T1 在榜且 viewCount ≥ 2）
        const hot = await analyst.get<any[]>('/governance/assets/hot-tables?limit=10');
        const t1 = hot.find(r => r.tableName === T1_NAME);
        expect(Number(t1.viewCount)).toBeGreaterThanOrEqual(2);
    });
});

// ==================== 导航与权限 ====================
test.describe('导航与权限', () => {
    test('全角色可见「我的收藏」「我的关注」菜单', async ({page}) => {
        for (const user of [TEST_USERS.engineer, TEST_USERS.analyst, TEST_USERS.govAdmin]) {
            await gotoAs(page, user.username, user.password, '/asset-catalog');
            await expect(page.getByRole('button', {name: '我的收藏'})).toBeVisible();
            await expect(page.getByRole('button', {name: '我的关注'})).toBeVisible();
        }
    });

    test('协作聚合端点（collaboration）字段完整', async () => {
        await analyst.post(`/governance/assets/tables/${T2_ID}/favorite`);
        const agg = await analyst.get<any>(`/governance/assets/tables/${T2_ID}/collaboration`);
        expect(agg.favorited).toBe(true);
        expect(agg.followed).toBe(false);
        expect(Number(agg.viewCount30d)).toBeGreaterThanOrEqual(1);
        expect(Array.isArray(agg.tags)).toBe(true);
        await analyst.del(`/governance/assets/tables/${T2_ID}/favorite`);
    });
});


// ==================== 删除语义与边界（F1 补充覆盖） ====================

test.describe('删除语义与边界', () => {
    /** 级联删除专用：临时数据源 + 临时元数据表（不与 T1~T5 冲突） */
    const CASCADE_DS_ID = '9000080000000000098';
    const CASCADE_TABLE_ID = '9000080000000000056';
    const CASCADE_TAG = 'e2e_s8_级联';

    function cleanCascade(): void {
        psqlGov(`DELETE FROM asset_table_tag WHERE table_id = ${CASCADE_TABLE_ID}`);
        psqlGov(`DELETE FROM asset_tag WHERE name = '${CASCADE_TAG}'`);
        psqlGov(`DELETE FROM asset_favorite WHERE table_id = ${CASCADE_TABLE_ID}`);
        psqlGov(`DELETE FROM asset_follow WHERE table_id = ${CASCADE_TABLE_ID}`);
        psqlGov(`DELETE FROM asset_comment WHERE table_id = ${CASCADE_TABLE_ID}`);
        psqlGov(`DELETE FROM asset_view_log WHERE table_id = ${CASCADE_TABLE_ID}`);
        psqlGov(`DELETE FROM metadata_column WHERE table_id = ${CASCADE_TABLE_ID}`);
        psqlGov(`DELETE FROM metadata_table WHERE id = ${CASCADE_TABLE_ID}`);
        psqlEng(`DELETE FROM datasource_connection WHERE id = ${CASCADE_DS_ID}`);
    }

    test('删除数据源级联清理元数据表与协作数据（T4）', async () => {
        cleanCascade();
        // 临时数据源（假连接即可，仅走删除级联链路）+ 临时元数据表
        psqlEng(`INSERT INTO datasource_connection
                 (id, name, type, host, port, database_name, schema_name, username, encrypted_password, status, created_at, updated_at, auto_collect_on_save)
                 VALUES (${CASCADE_DS_ID}, 'e2e_s8_cascade_ds', 'MYSQL', 'middleware-test-mysql', 3306, 'testdb', NULL, 'testuser', 'x', 'NORMAL', now(), now(), 0)`);
        psqlGov(`INSERT INTO metadata_table
                 (id, datasource_id, database_name, schema_name, table_name, table_comment, source_status, source_type, created_at, updated_at)
                 VALUES (${CASCADE_TABLE_ID}, ${CASCADE_DS_ID}, 'testdb', NULL, 'e2e_s8_cascade_tbl', '级联删除验证表', 'ONLINE', 'EXTERNAL', now(), now())`);
        // 打上全部协作数据：标签/收藏/关注/评论/热度
        await analyst.post(`/governance/assets/tables/${CASCADE_TABLE_ID}/tags`, {tagName: CASCADE_TAG});
        await analyst.post(`/governance/assets/tables/${CASCADE_TABLE_ID}/favorite`);
        await analyst.post(`/governance/assets/tables/${CASCADE_TABLE_ID}/follow`);
        await analyst.post(`/governance/assets/tables/${CASCADE_TABLE_ID}/comments`, {content: 'e2e_s8 级联评论'});
        await analyst.post(`/governance/assets/tables/${CASCADE_TABLE_ID}/view`);

        // 删除数据源（admin）→ 治理侧级联
        await admin.del(`/engineering/datasources/${CASCADE_DS_ID}`);
        expect(scalarGov(`SELECT COUNT(*) FROM metadata_table WHERE id = ${CASCADE_TABLE_ID}`)).toBe('0');
        expect(scalarGov(`SELECT COUNT(*) FROM asset_favorite WHERE table_id = ${CASCADE_TABLE_ID}`)).toBe('0');
        expect(scalarGov(`SELECT COUNT(*) FROM asset_follow WHERE table_id = ${CASCADE_TABLE_ID}`)).toBe('0');
        expect(scalarGov(`SELECT COUNT(*) FROM asset_comment WHERE table_id = ${CASCADE_TABLE_ID}`)).toBe('0');
        expect(scalarGov(`SELECT COUNT(*) FROM asset_view_log WHERE table_id = ${CASCADE_TABLE_ID}`)).toBe('0');
        expect(scalarGov(`SELECT COUNT(*) FROM asset_table_tag WHERE table_id = ${CASCADE_TABLE_ID}`)).toBe('0');
        // 孤儿标签物理删除
        expect(scalarGov(`SELECT COUNT(*) FROM asset_tag WHERE name = '${CASCADE_TAG}'`)).toBe('0');
        cleanCascade();
    });

    test('评论作者已注销兜底显示（T4：删用户保留评论）', async ({page}) => {
        // 造一条 user_id 不存在的历史评论（模拟作者账号已删）
        psqlGov(`INSERT INTO asset_comment (id, table_id, user_id, content, deleted, created_at)
                 VALUES (9000080000000000071, ${T1_ID}, 999999999999, 'e2e_s8 已注销用户的历史评论', 0, NOW())`);
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}?tab=comments`);
        await expect(page.getByText('e2e_s8 已注销用户的历史评论')).toBeVisible();
        await expect(page.getByLabel('评论').getByText('已注销', {exact: true})).toBeVisible();
        psqlGov(`DELETE FROM asset_comment WHERE id = 9000080000000000071`);
    });

    test('评论分页（API 辅助：page/pageSize/total）', async () => {
        // 清掉 T1 存量评论，造 12 条
        psqlGov(`DELETE FROM asset_comment WHERE table_id = ${T1_ID}`);
        for (let i = 1; i <= 12; i++) {
            await analyst.post(`/governance/assets/tables/${T1_ID}/comments`, {content: `e2e_s8 分页评论 ${i}`});
        }
        const page1 = await analyst.get<{ records: any[]; total: number }>(
            `/governance/assets/tables/${T1_ID}/comments?page=1&pageSize=10`);
        expect(Number(page1.total)).toBe(12);
        expect(page1.records.length).toBe(10);
        const page2 = await analyst.get<{ records: any[]; total: number }>(
            `/governance/assets/tables/${T1_ID}/comments?page=2&pageSize=10`);
        expect(page2.records.length).toBe(2);
        // 两页无重叠（DTO 主键字段为 commentId）
        const ids1 = new Set(page1.records.map(r => String(r.commentId)));
        for (const r of page2.records) expect(ids1.has(String(r.commentId))).toBe(false);
        psqlGov(`DELETE FROM asset_comment WHERE table_id = ${T1_ID}`);
    });
});
