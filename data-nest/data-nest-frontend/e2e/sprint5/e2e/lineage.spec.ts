import {expect, type Page, test} from '@playwright/test';
import {ADMIN, LINEAGE} from '../helpers/data';
import {gotoAs} from '../helpers/e2e';

const METADATA_TABLE_ID = '6500000000000000001';

async function gotoLineage(page: Page, tableName: string): Promise<void> {
    await gotoAs(page, ADMIN.username, ADMIN.password, `/governance/metadata/lineage?tableName=${encodeURIComponent(tableName)}`);
    await expect(page.getByRole('heading', {name: '血缘图谱'})).toBeVisible();
    // 等待图谱加载完成
    await expect(page.locator('.react-flow__node').first()).toBeVisible({timeout: 15000});
}

test.describe.configure({mode: 'serial'});

test.describe('血缘可视化 E2E', () => {
    test('AC-1 元数据详情页「血缘图谱」入口按钮跳转', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, `/governance/metadata?tableId=${METADATA_TABLE_ID}`);
        // 表详情加载，点击「血缘图谱」按钮
        const btn = page.getByRole('button', {name: /血缘图谱/});
        await expect(btn).toBeVisible({timeout: 15000});
        await btn.click();
        await expect(page.getByRole('heading', {name: '血缘图谱'})).toBeVisible({timeout: 15000});
        await expect(page).toHaveURL(/\/governance\/metadata\/lineage/);
        // 图谱渲染出节点
        await expect(page.locator('.react-flow__node')).toHaveCount(4, {timeout: 15000});
    });

    test('AC-2 表级血缘图谱渲染：节点与当前表高亮', async ({page}) => {
        await gotoLineage(page, LINEAGE.t1Target);
        // 4 个表节点
        await expect(page.locator('.react-flow__node')).toHaveCount(4);
        // 3 条血缘边
        await expect(page.locator('.react-flow__edge')).toHaveCount(3);
        // 当前表标签
        await expect(page.getByText('当前表')).toBeVisible();
        // 上下游节点名都在
        await expect(page.getByText(LINEAGE.t1Source, {exact: false}).first()).toBeVisible();
        await expect(page.getByText(LINEAGE.t3Target, {exact: false}).first()).toBeVisible();
    });

    test('AC-4 影响分析：高亮下游链路', async ({page}) => {
        await gotoLineage(page, LINEAGE.t1Target);
        await page.getByRole('button', {name: '影响分析'}).click();
        // 点击下游表节点 dws_order_summary
        await page.locator('.react-flow__node').filter({hasText: LINEAGE.t3Target}).click();
        // 提示语切换为分析模式
        await expect(page.getByText(/点击节点查看其下游影响链路/)).toBeVisible();
        // 高亮节点：dwd_orders 与 dws_order_summary 获得绿色高亮（border-success）
        await expect(
            page.locator('.react-flow__node').filter({hasText: LINEAGE.t3Target}).locator('.border-ds-success'),
        ).toBeVisible({timeout: 15000});
        // 边仍然存在
        await expect(page.locator('.react-flow__edge')).toHaveCount(3);
    });

    test('AC-5 溯源分析：高亮上游链路', async ({page}) => {
        await gotoLineage(page, LINEAGE.t3Target);
        await page.getByRole('button', {name: '溯源分析'}).click();
        // 点击上游表节点 dwd_orders
        await page.locator('.react-flow__node').filter({hasText: LINEAGE.t1Target}).click();
        await expect(page.getByText(/点击节点查看其上游溯源链路/)).toBeVisible();
        await expect(
            page.locator('.react-flow__node').filter({hasText: LINEAGE.t1Target}).locator('.border-ds-success'),
        ).toBeVisible({timeout: 15000});
        // 边仍然存在
        await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    });

    test('AC-6 字段级血缘下钻面板', async ({page}) => {
        // 带 tableId 以便面板自动加载字段列表并默认选中第一个字段
        await gotoAs(page, ADMIN.username, ADMIN.password, `/governance/metadata/lineage?tableId=${METADATA_TABLE_ID}&tableName=${encodeURIComponent(LINEAGE.t1Target)}`);
        await expect(page.getByRole('heading', {name: '血缘图谱'})).toBeVisible();
        await expect(page.locator('.react-flow__node').first()).toBeVisible({timeout: 15000});
        await page.getByRole('button', {name: '字段血缘'}).click();
        await expect(page.getByRole('heading', {name: '字段级血缘'})).toBeVisible();
        // 字段下拉默认选第一个字段（id），渲染字段链路（当前字段标签）
        await expect(page.getByText('当前字段')).toBeVisible({timeout: 15000});
        // 存在来源字段节点（ods_orders.id）
        await expect(page.getByText(`${LINEAGE.t1Source}.id`)).toBeVisible();
        // 字段级链路边渲染
        await expect(page.getByTestId('field-lineage-flow').locator('.react-flow__edge')).toHaveCount(1);
    });

    test('AC-7 无血缘表：展示空状态提示', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, `/governance/metadata/lineage?tableName=${encodeURIComponent(LINEAGE.orphan)}`);
        await expect(page.getByRole('heading', {name: '血缘图谱'})).toBeVisible();
        await expect(page.getByText('暂无血缘数据')).toBeVisible({timeout: 15000});
        await expect(page.getByText(/可通过以下方式产生血缘/)).toBeVisible();
    });

    test('AC-20 权限：分析师可查看血缘图谱', async ({page}) => {
        await gotoAs(page, 's5_analyst', 'Test123456', `/governance/metadata/lineage?tableName=${encodeURIComponent(LINEAGE.t1Target)}`);
        await expect(page.locator('.react-flow__node')).toHaveCount(4, {timeout: 15000});
        await expect(page.locator('.react-flow__edge')).toHaveCount(3);
    });
});
