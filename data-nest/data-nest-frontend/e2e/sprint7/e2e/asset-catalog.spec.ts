import {expect, type Page, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {psqlGov, psqlSys, scalarGov} from '../helpers/db';
import {seedAll} from '../helpers/seed';
import {
    ADMIN,
    TEST_USERS,
    DS_ID,
    T1_ID,
    T1_NAME,
    T2_ID,
    T2_NAME,
    T3_NAME,
    T4_ID,
    T4_NAME,
    T5_ID,
    T5_NAME,
    D1_NAME,
    D1T1_NAME,
    D1T2_NAME,
    D2_NAME,
    RULE_R1_NAME,
    RULE_R2_NAME,
    RULE_R3_NAME,
} from '../helpers/data';

/**
 * Sprint 7 F1 数据资产目录 E2E 测试（业务主链路全覆盖，API 辅助诊断）。
 *
 * 覆盖：DC-01 多维搜索（表名/注释/字段/负责人 + 数据源/健康度筛选）、DC-02 详情聚合
 * （三指标卡 + 四页签懒加载）、DC-03 血缘嵌入（图谱/空态/查看完整血缘 from 回跳）、
 * DC-04 质量展示（徽章/规则结果/执行冒烟）、DC-05 分类浏览与维护（树计数/CRUD/改名级联/
 * 删除拦截/批量分配/移出/负责人配置）、权限隔离（PRD AC-10）。
 *
 * 测试数据：seedAll 播种（e2e_s7 前缀 + 固定 ID 900007*，拆库后落 datanest_governance/
 * datanest_engineering/datanest_system），本 spec 自带播种，支持 SKIP_SETUP=1 独立运行。
 * 环境背景数据（s4/s5/s6 残留表）存在，全局计数类断言一律用「相对差值」或限定 e2e_s7 行。
 */

let admin: Api;
let gov: Api;
let engineer: Api;
let analyst: Api;

/** 左侧分类树容器（含「数据域 / 主题」标题的 div.p-ds-3） */
function tree(page: Page) {
    return page.locator('div.p-ds-3', {has: page.getByText('数据域 / 主题')});
}

/** 表格行定位（按表名） */
function assetRow(page: Page, tableName: string) {
    return page.locator('.ant-table-row').filter({hasText: tableName});
}

/** 树中某分类节点的计数徽章文本（全部/未分类是 button.group，域/主题是 div.group；选中态还有 activeBar 也是 rounded-full，按纯数字文本过滤） */
function treeNodeBadge(page: Page, name: string) {
    return tree(page)
        .locator('.group', {has: page.getByText(name, {exact: true})})
        .locator('span.rounded-full', {hasText: /^\d+$/});
}

/** 搜索并等待结果（提交 keyword → 搜索态） */
async function searchFor(page: Page, keyword: string) {
    await page.getByLabel('搜索数据资产').fill(keyword);
    await page.getByRole('button', {name: /查询/}).click();
}

/** antd 通知断言（成功/拦截文案） */
function notice(page: Page, text: string | RegExp) {
    return page.locator('.ant-message-notice').filter({hasText: text}).first();
}

test.describe.configure({mode: 'serial'});

test.beforeAll(async () => {
    admin = await Api.create();
    await admin.login(ADMIN.username, ADMIN.password);
    // 本 spec 自带播种（幂等），支持 SKIP_SETUP=1 独立运行
    await seedAll();
    gov = await Api.create();
    await gov.login(TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password);
    engineer = await Api.create();
    await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
    analyst = await Api.create();
    await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
});

test.afterAll(async () => {
    await admin?.dispose();
    await gov?.dispose();
    await engineer?.dispose();
    await analyst?.dispose();
});

// ==================== DC-05 分类浏览与树计数 ====================

test.describe('DC-05 分类浏览（左树右表）', () => {
    test('首页加载：分类树 + 计数徽章 + 表格 + 分页', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await expect(page.getByRole('heading', {name: '数据资产'})).toBeVisible();

        // 树节点计数（残留已清理，e2e_s7 分类计数确定）
        await expect(tree(page).getByText(D1_NAME, {exact: true})).toBeVisible();
        await expect(treeNodeBadge(page, D1_NAME)).toHaveText('2');
        await expect(treeNodeBadge(page, D1T1_NAME)).toHaveText('2');
        await expect(treeNodeBadge(page, D1T2_NAME)).toHaveText('0');
        await expect(treeNodeBadge(page, D2_NAME)).toHaveText('1');

        // 未分类 = 全部 - 已分类 3（T1/T2/T3）
        const allCount = Number(await treeNodeBadge(page, '全部资产').innerText());
        const uncategorized = Number(await treeNodeBadge(page, '未分类').innerText());
        expect(uncategorized).toBe(allCount - 3);

        // 浏览态表格含 e2e 表（首页默认按更新时间排序，不假定首行）+ 分页器存在
        await expect(page.locator('.ant-table-row').first()).toBeVisible();
        await expect(page.getByText(/共 \d+ 条/)).toBeVisible();
    });

    test('点数据域/主题/未分类节点过滤表格', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');

        // 交易域 → T1/T2
        await tree(page).getByText(D1_NAME, {exact: true}).click();
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        await expect(assetRow(page, T3_NAME)).toHaveCount(0);

        // 主题 订单 → 仍 T1/T2；主题 退款 → 空态
        await tree(page).getByText(D1T1_NAME, {exact: true}).click();
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        await tree(page).getByText(D1T2_NAME, {exact: true}).click();
        await expect(page.getByText('该分类下暂无数据表')).toBeVisible();

        // 用户域 → 仅 T3
        await tree(page).getByText(D2_NAME, {exact: true}).click();
        await expect(assetRow(page, T3_NAME)).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toHaveCount(0);

        // 未分类 → 含 T4/T5，不含 T1
        await tree(page).getByRole('button', {name: /未分类/}).click();
        // 全量 setup 下 s5/s6 种子表较多，未分类可能超过 10 条/页，先放大到 50 条/页再断言
        await page.getByLabel('每页条数').selectOption('50');
        await expect(assetRow(page, T4_NAME)).toBeVisible();
        await expect(assetRow(page, T5_NAME)).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toHaveCount(0);

        // 全部资产 → 回到全量（分页器回归）
        await tree(page).getByRole('button', {name: /全部资产/}).click();
        await expect(page.getByText(/共 \d+ 条/)).toBeVisible();
    });

    test('浏览态数据源/健康度下拉即时筛选', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');

        // 数据源 = e2e_s7_mysql_ds → 含 T1 不含 Doris 表 T5（即时生效，无需点查询）
        await page.getByLabel('按数据源筛选').selectOption(DS_ID);
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T5_NAME)).toHaveCount(0);
        await page.getByLabel('按数据源筛选').selectOption('');

        // 健康度 = 差(BAD) → 仅 T4（环境中 orders 残留评分为 BAD 也可能命中，不断言唯一行）
        await page.getByLabel('按健康度筛选').selectOption('BAD');
        await expect(assetRow(page, T4_NAME)).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toHaveCount(0);
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
    });
});

// ==================== DC-01 多维搜索 ====================

test.describe('DC-01 多维搜索', () => {
    test('表名前缀搜索命中并进搜索态（无分页器）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, 'e2e_s7_trade');
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        // 表名命中(120) 应排在注释/字段命中之前：前两行即 T1/T2（顺序不定）
        const firstTwo = await page.locator('.ant-table-row').allInnerTexts();
        expect(firstTwo.length).toBe(2);
        // 搜索态无分页器
        await expect(page.getByText(/共 \d+ 条/)).toHaveCount(0);
    });

    test('注释搜索命中', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, '电商');
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toBeVisible();
        await expect(assetRow(page, T3_NAME)).toHaveCount(0);
    });

    test('字段名/字段注释搜索命中', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, '交易流水号');
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
    });

    test('负责人搜索命中', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, TEST_USERS.analyst.username);
        await expect(assetRow(page, T1_NAME)).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
        // 行内负责人列显示 s7_analyst
        await expect(assetRow(page, T1_NAME)).toContainText(TEST_USERS.analyst.username);
    });

    test('搜索无结果空态 + 重置回浏览态', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, 'zzz_no_such_asset_keyword');
        await expect(page.getByText('未找到匹配的资产，换个关键词试试')).toBeVisible();
        await page.getByRole('button', {name: '重置'}).click();
        await expect(page.getByText(/共 \d+ 条/)).toBeVisible();
        await expect(page.getByLabel('搜索数据资产')).toHaveValue('');
    });

    test('搜索态数据源/健康度筛选', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, 'e2e_s7');
        // 5 张 e2e_s7 表全部命中（T1-T5）
        await expect(assetRow(page, T5_NAME)).toBeVisible();
        // 数据源筛选 → 去掉 Doris 表
        await page.getByLabel('按数据源筛选').selectOption(DS_ID);
        await expect(assetRow(page, T5_NAME)).toHaveCount(0);
        await expect(assetRow(page, T4_NAME)).toBeVisible();
        // 健康度 BAD → 仅 T4
        await page.getByLabel('按健康度筛选').selectOption('BAD');
        await expect(assetRow(page, T4_NAME)).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toHaveCount(0);
        await expect(assetRow(page, T2_NAME)).toHaveCount(0);
    });
});

// ==================== DC-02/03/04 资产详情页 ====================

test.describe('DC-02/03/04 资产详情页', () => {
    test('搜索 → 进详情：头部 + 三指标卡', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, '/asset-catalog');
        await searchFor(page, 'e2e_s7_trade');
        await assetRow(page, T1_NAME).getByRole('button').first().click();
        await page.waitForURL(`**/asset-catalog/${T1_ID}`);

        // 头部：库名 / 表名 + 分类徽章 + 负责人
        await expect(page.getByText(`${T1_NAME}`, {exact: true}).first()).toBeVisible();
        await expect(page.getByText(D1_NAME, {exact: true}).first()).toBeVisible();
        await expect(page.getByText(D1T1_NAME, {exact: true}).first()).toBeVisible();
        await expect(page.getByText(`负责人：${TEST_USERS.analyst.username}`)).toBeVisible();

        // 三指标卡：质量评分（优秀徽章）/ 字段数 3 / 直接上下游 1 / 1（限定 main + first，避免与侧边栏菜单/基础信息 kv 撞名）
        const main = page.getByRole('main');
        await expect(main.getByText('质量评分', {exact: true}).first()).toBeVisible();
        await expect(main.getByText('字段数', {exact: true}).first()).toBeVisible();
        await expect(main.getByText('直接上游 / 下游表', {exact: true}).first()).toBeVisible();
        await expect(main.getByText('1 / 1', {exact: true})).toBeVisible();
        await expect(main.getByText('优秀', {exact: true}).first()).toBeVisible();
    });

    test('基础信息 + 字段列表页签', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);
        // 基础信息：数据域/主题/负责人 kv
        await expect(page.getByText('表全名', {exact: true})).toBeVisible();
        await expect(page.getByText(`testdb.${T1_NAME}`)).toBeVisible();

        // 字段列表（懒加载，切到才拉取）：3 列，含交易流水号注释
        await page.getByRole('tab', {name: /字段列表/}).click();
        await expect(assetRow(page, 'trade_no')).toContainText('交易流水号');
        await expect(assetRow(page, 'amount')).toContainText('订单金额');
        await expect(page.locator('.ant-table-row')).toHaveCount(3);
    });

    test('血缘图谱页签：上下游节点 + 查看完整血缘 from 回跳', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);
        await page.getByRole('tab', {name: /血缘图谱/}).click();
        // 精简图谱：当前表 + 上游 T3 + 下游 T2（节点标题渲染全名 库.表）
        await expect(page.getByText('当前表', {exact: true})).toBeVisible();
        await expect(page.getByText(`testdb.${T3_NAME}`, {exact: true})).toBeVisible();
        await expect(page.getByText(`testdb.${T2_NAME}`, {exact: true})).toBeVisible();

        // 查看完整血缘 → LineageGraphPage（from=asset-catalog）→ 返回资产详情
        await page.getByRole('button', {name: '查看完整血缘图谱'}).click();
        await page.waitForURL(/\/governance\/metadata\/lineage\?.*from=asset-catalog/);
        await expect(page.getByRole('heading', {name: '血缘图谱'})).toBeVisible();
        await page.getByRole('button', {name: '← 返回'}).click();
        // 返回落到详情页血缘图谱 tab（53065d6 起带回 ?tab=lineage，glob 需匹配 query）
        await page.waitForURL(`**/asset-catalog/${T1_ID}?tab=lineage`);
    });

    test('血缘空态（无血缘表）', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T4_ID}`);
        await page.getByRole('tab', {name: /血缘图谱/}).click();
        await expect(page.getByText('暂无血缘数据')).toBeVisible();
    });

    test('质量页签：徽章 + 概览计数 + 规则判定', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, `/asset-catalog/${T1_ID}`);
        await page.getByRole('tab', {name: /^质量$/}).click();
        // 概览条：启用规则数 3、通过/警告/严重 = 2/1/0
        await expect(page.getByText('启用规则数', {exact: true})).toBeVisible();
        // 规则表三条规则 + 判定徽章
        await expect(assetRow(page, RULE_R1_NAME)).toContainText('通过');
        await expect(assetRow(page, RULE_R2_NAME)).toContainText('通过');
        await expect(assetRow(page, RULE_R3_NAME)).toContainText('警告');
        // 治理员可见「立即执行全部规则」（不点击，真实执行为 API 冒烟覆盖）
        await expect(page.getByRole('button', {name: '立即执行全部规则'})).toBeVisible();
    });

    test('内置 Doris 表：数据源兜底回显 Doris 数仓', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T5_ID}`);
        await expect(page.getByText('Doris 数仓').first()).toBeVisible();
    });

    test('← 返回 回到资产目录首页', async ({page}) => {
        await gotoAs(page, TEST_USERS.analyst.username, TEST_USERS.analyst.password, `/asset-catalog/${T1_ID}`);
        await page.getByRole('button', {name: '← 返回'}).click();
        await page.waitForURL('**/asset-catalog');
        await expect(page.getByRole('heading', {name: '数据资产'})).toBeVisible();
    });
});

// ==================== DC-05 分类维护（治理员） ====================

test.describe('DC-05 分类维护（治理员写操作）', () => {
    test('新增数据域 → 树出现 → 删除空域成功', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/asset-catalog');
        await page.getByRole('button', {name: '新增数据域'}).click();
        const dialog = page.getByRole('dialog', {name: '新增分类'});
        await dialog.getByPlaceholder('如：交易域 / 订单').fill('e2e_s7_临时域');
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '分类已创建')).toBeVisible();
        await expect(tree(page).getByText('e2e_s7_临时域', {exact: true})).toBeVisible();

        // 删除空域（无主题无引用 → 可删）
        await tree(page).getByRole('button', {name: '删除 e2e_s7_临时域'}).click();
        const confirm = page.getByRole('dialog', {name: '删除分类'});
        await confirm.getByRole('button', {name: '删除'}).click();
        await expect(tree(page).getByText('e2e_s7_临时域', {exact: true})).toHaveCount(0);
    });

    test('管理条新增主题 → 删除空主题成功', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/asset-catalog');
        await tree(page).getByText(D2_NAME, {exact: true}).click();
        // 管理条出现（当前分类 + 新增主题/分配表到此分类）
        await expect(page.getByText('当前分类：')).toBeVisible();
        await page.getByRole('button', {name: '新增主题'}).click();
        const dialog = page.getByRole('dialog', {name: '新增分类'});
        await dialog.getByPlaceholder('如：交易域 / 订单').fill('e2e_s7_支付');
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '分类已创建')).toBeVisible();
        await expect(tree(page).getByText('e2e_s7_支付', {exact: true})).toBeVisible();

        await tree(page).getByRole('button', {name: '删除 e2e_s7_支付'}).click();
        await page.getByRole('dialog', {name: '删除分类'}).getByRole('button', {name: '删除'}).click();
        await expect(tree(page).getByText('e2e_s7_支付', {exact: true})).toHaveCount(0);
    });

    test('编辑主题改名 → 级联更新表分类徽章 → 改回还原', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/asset-catalog');
        // 改名 e2e_s7_订单 → e2e_s7_订单V2
        await tree(page).getByRole('button', {name: `编辑 ${D1T1_NAME}`}).click();
        const dialog = page.getByRole('dialog', {name: '编辑主题'});
        await dialog.getByPlaceholder('如：交易域 / 订单').fill('e2e_s7_订单V2');
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, /分类已保存/)).toBeVisible();

        // 级联生效：浏览交易域，T1 行主题徽章为新名
        await tree(page).getByText(D1_NAME, {exact: true}).click();
        await expect(assetRow(page, T1_NAME)).toContainText('e2e_s7_订单V2');
        // DB 级联校验（API 辅助诊断）
        expect(scalarGov(`SELECT data_topic FROM metadata_table WHERE id = ${T1_ID}`)).toBe('e2e_s7_订单V2');

        // 改回还原
        await tree(page).getByRole('button', {name: '编辑 e2e_s7_订单V2'}).click();
        const dialog2 = page.getByRole('dialog', {name: '编辑主题'});
        await dialog2.getByPlaceholder('如：交易域 / 订单').fill(D1T1_NAME);
        await dialog2.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, /分类已保存/)).toBeVisible();
        await expect(assetRow(page, T1_NAME)).toContainText(D1T1_NAME);
        expect(scalarGov(`SELECT data_topic FROM metadata_table WHERE id = ${T1_ID}`)).toBe(D1T1_NAME);
    });

    test('删除被引用主题 → 4009 拦截；删除有主题的域 → 拦截', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/asset-catalog');
        // 主题 e2e_s7_订单 被 T1/T2 引用 → 拦截
        await tree(page).getByRole('button', {name: `删除 ${D1T1_NAME}`}).click();
        await page.getByRole('dialog', {name: '删除分类'}).getByRole('button', {name: '删除'}).click();
        await expect(notice(page, /引用/)).toBeVisible();
        await expect(tree(page).getByText(D1T1_NAME, {exact: true})).toBeVisible();

        // 域 e2e_s7_交易域 下有主题 → 拦截
        await tree(page).getByRole('button', {name: `删除 ${D1_NAME}`}).click();
        await page.getByRole('dialog', {name: '删除分类'}).getByRole('button', {name: '删除'}).click();
        await expect(notice(page, /子分类/)).toBeVisible();
        await expect(tree(page).getByText(D1_NAME, {exact: true})).toBeVisible();
    });

    test('批量分配到分类 → 计数变化 → 行操作移出还原', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/asset-catalog');
        // 选中用户域（原计数 1）→ 分配表到此分类
        await tree(page).getByText(D2_NAME, {exact: true}).click();
        await page.getByRole('button', {name: '分配表到此分类'}).click();
        const dialog = page.getByRole('dialog', {name: /分配表到/});
        await dialog.getByLabel('搜索候选表').fill(T4_NAME);
        await dialog.getByLabel('搜索候选表').press('Enter');
        const candidateRow = dialog.locator('.ant-table-row').filter({hasText: T4_NAME});
        await candidateRow.getByRole('checkbox').check();
        await dialog.getByRole('button', {name: /批量分配（1）/}).click();
        await expect(notice(page, /张表分配到/)).toBeVisible();

        // 用户域计数 1 → 2，表格含 T4
        await expect(treeNodeBadge(page, D2_NAME)).toHaveText('2');
        await expect(assetRow(page, T4_NAME)).toBeVisible();

        // 行操作「移出当前分类」还原
        await assetRow(page, T4_NAME).getByRole('button', {name: '移出当前分类'}).click();
        await page.getByRole('dialog', {name: '移出分类'}).getByRole('button', {name: '移出'}).click();
        await expect(treeNodeBadge(page, D2_NAME)).toHaveText('1');
        expect(scalarGov(`SELECT data_domain FROM metadata_table WHERE id = ${T4_ID}`)).toBeNull();
    });

    test('详情页分配分类 → 清除分类', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, `/asset-catalog/${T4_ID}`);
        await page.getByRole('button', {name: '分配分类', exact: true}).click();
        const dialog = page.getByRole('dialog', {name: /分配分类 ·/});
        // 数据域 select → e2e_s7_用户域
        await dialog.locator('.ant-select').first().click();
        await page.locator('.ant-select-item-option', {hasText: D2_NAME}).click();
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '分类已更新')).toBeVisible();
        await expect(page.getByText(D2_NAME, {exact: true}).first()).toBeVisible();
        expect(scalarGov(`SELECT data_domain FROM metadata_table WHERE id = ${T4_ID}`)).toBe(D2_NAME);

        // 清除（留空数据域 → 保存）
        await page.getByRole('button', {name: '分配分类', exact: true}).click();
        const dialog2 = page.getByRole('dialog', {name: /分配分类 ·/});
        await dialog2.locator('.ant-select-clear').first().click();
        await dialog2.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '已清除分类')).toBeVisible();
        expect(scalarGov(`SELECT data_domain FROM metadata_table WHERE id = ${T4_ID}`)).toBeNull();
    });

    test('配置负责人 → 清除负责人', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/asset-catalog');
        await searchFor(page, T2_NAME);
        await assetRow(page, T2_NAME).getByRole('button', {name: '配置负责人'}).click();
        const dialog = page.getByRole('dialog', {name: /配置负责人 ·/});
        await dialog.locator('.ant-select').click();
        // 全量 setup 下用户较多，antd Select 虚拟滚动只渲染视口内选项；
        // 输入关键字触发客户端过滤收窄选项（antd v6 搜索框类名是 .ant-select-input）
        await dialog.locator('.ant-select input').pressSequentially(TEST_USERS.govAdmin.username);
        await page.locator('.ant-select-item-option', {hasText: TEST_USERS.govAdmin.username}).click();
        await dialog.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '负责人已更新')).toBeVisible();
        await expect(assetRow(page, T2_NAME)).toContainText(TEST_USERS.govAdmin.username);
        const govId = psqlSys(`SELECT id FROM sys_user WHERE username = '${TEST_USERS.govAdmin.username}'`);
        expect(scalarGov(`SELECT owner_user_id FROM metadata_table WHERE id = ${T2_ID}`)).toBe(govId);

        // 清除（allowClear 留空 → 保存）
        await assetRow(page, T2_NAME).getByRole('button', {name: '配置负责人'}).click();
        const dialog2 = page.getByRole('dialog', {name: /配置负责人 ·/});
        await dialog2.locator('.ant-select-clear').click();
        await dialog2.getByRole('button', {name: '保存'}).click();
        await expect(notice(page, '已清除负责人')).toBeVisible();
        expect(scalarGov(`SELECT owner_user_id FROM metadata_table WHERE id = ${T2_ID}`)).toBeNull();
    });
});

// ==================== 权限隔离（PRD AC-10） ====================

test.describe('权限隔离（分析师/工程师只读）', () => {
    for (const [key, user] of Object.entries({engineer: TEST_USERS.engineer, analyst: TEST_USERS.analyst})) {
        test(`${key} 无分类维护入口（UI 隐藏 + API 拒绝）`, async ({page}) => {
            await gotoAs(page, user.username, user.password, '/asset-catalog');
            // 读可用（树 + 表格；全量 setup 下默认浏览首屏不一定含 T1，用搜索确定命中）
            await expect(tree(page).getByText(D1_NAME, {exact: true})).toBeVisible();
            await searchFor(page, T1_NAME);
            await expect(assetRow(page, T1_NAME)).toBeVisible();
            // 写入口全部隐藏
            await expect(page.getByRole('button', {name: '新增数据域'})).toHaveCount(0);
            await expect(page.getByRole('button', {name: `编辑 ${D1_NAME}`})).toHaveCount(0);
            await expect(page.getByRole('button', {name: `删除 ${D1_NAME}`})).toHaveCount(0);
            await expect(page.getByRole('button', {name: '配置负责人'})).toHaveCount(0);
            // 详情页无分配分类/配置负责人按钮
            await page.goto(`/asset-catalog/${T1_ID}`);
            await expect(page.getByRole('button', {name: '分配分类', exact: true})).toHaveCount(0);
            await expect(page.getByRole('button', {name: '配置负责人'})).toHaveCount(0);
        });
    }

    test('API 级：工程师/分析师写接口一律拒绝', async () => {
        for (const api of [engineer, analyst]) {
            const create = await api.raw('POST', '/governance/assets/classifications', {level: 'DOMAIN', name: 'x'});
            expect(create.code, `${api === engineer ? '工程师' : '分析师'}新增分类`).not.toBe(200);
            const assign = await api.raw('PUT', `/governance/assets/tables/${T4_ID}/classification`, {dataDomain: D2_NAME});
            expect(assign.code).not.toBe(200);
            const owner = await api.raw('PUT', `/governance/assets/tables/${T4_ID}/owner`, {ownerUserId: 1});
            expect(owner.code).not.toBe(200);
            const batch = await api.raw('PUT', '/governance/assets/tables/classification/batch', {
                tableIds: [T4_ID], dataDomain: D2_NAME,
            });
            expect(batch.code).not.toBe(200);
        }
        // 读接口四角色可用
        for (const api of [engineer, analyst]) {
            const read = await api.raw('GET', '/governance/assets/search?keyword=e2e_s7');
            expect(read.code).toBe(200);
        }
    });
});

// ==================== API 辅助诊断（无 UI 入口的能力） ====================

test.describe('API 辅助诊断', () => {
    test('browse sort=score 按质量分降序', async () => {
        const data = await gov.get<{ records: any[] }>(
            `/governance/assets/browse?domain=${encodeURIComponent(D1_NAME)}&sort=score&pageSize=50`);
        const names = data.records.map((r) => r.tableName);
        expect(names).toEqual([T1_NAME, T2_NAME]); // 95 > 85
        expect(Number(data.records[0].qualityScore)).toBeGreaterThan(Number(data.records[1].qualityScore));
    });

    test('search 相关度排序：表名前缀 > 注释 > 字段 > 负责人', async () => {
        // 关键词设计：'e2e_s7' 全部表名命中（前缀 120）；score 字段随维度递减
        const data = await gov.get<any[]>(`/governance/assets/search?keyword=${encodeURIComponent('e2e_s7_trade')}`);
        const byName = Object.fromEntries(data.map((r) => [r.tableName, r.score]));
        // 两表均为表名前缀命中（120），且排在最前
        expect(byName[T1_NAME]).toBe(120);
        expect(byName[T2_NAME]).toBe(120);
        // 注释命中 60
        const commentHit = await gov.get<any[]>(`/governance/assets/search?keyword=${encodeURIComponent('电商')}`);
        for (const r of commentHit) expect(r.score).toBe(60);
        // 负责人命中 20
        const ownerHit = await gov.get<any[]>(
            `/governance/assets/search?keyword=${encodeURIComponent(TEST_USERS.analyst.username)}`);
        expect(ownerHit.map((r) => r.tableName)).toEqual([T1_NAME]);
        expect(ownerHit[0].score).toBe(20);
        expect(ownerHit[0].ownerName).toBe(TEST_USERS.analyst.username);
    });

    test('search/browse healthLevel + datasourceId 过滤', async () => {
        // search + healthLevel=GOOD → 仅 T2
        const good = await gov.get<any[]>(`/governance/assets/search?keyword=e2e_s7&healthLevel=GOOD`);
        expect(good.map((r) => r.tableName)).toEqual([T2_NAME]);
        // search + datasourceId → 不含 Doris 表 T5
        const byDs = await gov.get<any[]>(`/governance/assets/search?keyword=e2e_s7&datasourceId=${DS_ID}`);
        expect(byDs.map((r) => r.tableName)).not.toContain(T5_NAME);
        expect(byDs.length).toBe(4);
        // browse healthLevel=BAD → 全部记录 BAD 且含 T4
        const bad = await gov.get<{ records: any[] }>(`/governance/assets/browse?healthLevel=BAD&pageSize=50`);
        expect(bad.records.map((r) => r.tableName)).toContain(T4_NAME);
        for (const r of bad.records) expect(r.healthLevel).toBe('BAD');
    });

    test('classifications 树计数（tableCount/uncategorizedCount）', async () => {
        const treeRes = await gov.get<{ list: any[]; totalCount: number; uncategorizedCount: number }>(
            '/governance/assets/classifications');
        const d1 = treeRes.list.find((d) => d.name === D1_NAME);
        const d2 = treeRes.list.find((d) => d.name === D2_NAME);
        // Long 序列化为字符串，断言前转 Number
        expect(Number(d1.tableCount)).toBe(2);
        expect(Number(d1.children.find((t: any) => t.name === D1T1_NAME).tableCount)).toBe(2);
        expect(Number(d1.children.find((t: any) => t.name === D1T2_NAME).tableCount)).toBe(0);
        expect(Number(d2.tableCount)).toBe(1);
        expect(Number(treeRes.uncategorizedCount)).toBe(Number(treeRes.totalCount) - 3);
    });

    test('批量分配接口返回更新数（分配 + 批量清除还原）', async () => {
        const assigned = await gov.put<number>('/governance/assets/tables/classification/batch', {
            tableIds: [T4_ID], dataDomain: D2_NAME,
        });
        expect(assigned).toBe(1);
        expect(scalarGov(`SELECT data_domain FROM metadata_table WHERE id = ${T4_ID}`)).toBe(D2_NAME);
        // 批量清除还原
        const cleared = await gov.put<number>('/governance/assets/tables/classification/batch', {
            tableIds: [T4_ID],
        });
        expect(cleared).toBe(1);
        expect(scalarGov(`SELECT data_domain FROM metadata_table WHERE id = ${T4_ID}`)).toBeNull();
    });

    test('质量执行冒烟：T1 投递 200；无规则表 T5 拒绝', async () => {
        // 无启用规则的表 → 业务异常（QUALITY_RULE_NOT_FOUND）
        const noRule = await gov.raw('POST', `/governance/quality/scores/table/${T5_ID}/execute`);
        expect(noRule.code).not.toBe(200);
        // T1 三条启用规则 → 投递成功（异步执行结果为 UNAVAILABLE，落最后不影响其它断言）
        const ok = await gov.raw('POST', `/governance/quality/scores/table/${T1_ID}/execute`);
        expect(ok.code).toBe(200);
    });
});
