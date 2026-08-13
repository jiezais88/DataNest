import {expect, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {Api} from '../../sprint6/helpers/api';
import {ADMIN, TARGET, getTargetTableId} from './helpers/f2-seed';
import {
    F3_PREFIX,
    F3_PATH_PREFIX,
    openApiCall,
    createApi,
    createKey,
    adminAction,
    cleanupF3,
    cleanupF3CallLog,
} from './helpers/f3-seed';

/**
 * Sprint 10 F3 E2E：API 调用统计（单 API 统计 + 全局运行统计 + 前端观测页）。
 *
 * 覆盖验收点（对齐 PRD §6.5 §9.1 AC-9 + 技术文档 D-D8 §5.1）：
 * - 调用统计异步写入 api_call_log（调用后轮询落地）
 * - 单 API /apis/{id}/stats：总调用/成功率/P95/今日/趋势/最近明细（异常高亮）/Key 排行/状态码三档
 * - 全局 /stats/*：overview/trend/health-distribution/top-apis/error-codes/top-keys/rate-limit-trend
 * - 前端：API 运行统计页（/data-service/api-stats）渲染 + API 详情页调用统计区块
 *
 * 环境约定：
 * - 自播种自清理；单 API 统计用全新 API（口径确定），全局统计用「包含/相对」断言（容忍历史数据）
 * - 调用样本：统计Key 3×200 + 限流Key 1×200 + 1×429 = 共 5 次（成功率 0.8、错误率 0.2）
 * - 串行执行
 */

test.describe.configure({mode: 'serial'});

let statsApiId = '';
let statsKey = ''; // 正常 Key（明文）

const STATS_PATH = `${F3_PATH_PREFIX}stats`;
const STATS_API_NAME = `${F3_PREFIX}统计API`;

// ==================== 小工具 ====================

/** 轮询单 API 统计直到 totalCalls 达标（异步写入有延迟） */
async function pollApiStats(api: Api, apiId: string, expectTotal: number): Promise<any> {
    for (let i = 0; i < 40; i++) {
        const stats = await api.get(`/data-service/apis/${apiId}/stats?range=24h`);
        if (Number(stats.totalCalls) >= expectTotal) return stats;
        await new Promise(r => setTimeout(r, 500));
    }
    throw new Error(`单 API 统计未在超时内落地: apiId=${apiId}`);
}

// ==================== 播种/清理 ====================

test.beforeAll(async () => {
    cleanupF3CallLog(); // 先清调用统计（此时 F3 API/Key 尚在，可关联删除）
    cleanupF3();

    const stats = await createApi({
        datasourceId: TARGET.datasourceId,
        databaseName: TARGET.databaseName,
        tableName: TARGET.tableName,
        metadataTableId: getTargetTableId(),
        name: STATS_API_NAME,
        path: STATS_PATH,
        paginated: 1,
        pageSizeMax: 10,
    });
    statsApiId = stats.id;
    await adminAction('POST', `/data-service/apis/${statsApiId}/publish`);

    const nk = await createKey({name: `${F3_PREFIX}统计Key`, qpsLimit: 1000, apiIds: [statsApiId]});
    statsKey = nk.apiKey;
    const rk = await createKey({name: `${F3_PREFIX}统计限流Key`, qpsLimit: 1, apiIds: [statsApiId]});

    // 生成调用样本：3×200（正常 Key）+ 1×200 + 1×429（限流 Key QPS=1）
    await openApiCall(STATS_PATH, statsKey, {pageSize: '5'});
    await openApiCall(STATS_PATH, statsKey, {pageSize: '5'});
    await openApiCall(STATS_PATH, statsKey, {pageSize: '5'});
    await openApiCall(STATS_PATH, rk.apiKey, {pageSize: '5'});
    await openApiCall(STATS_PATH, rk.apiKey, {pageSize: '5'}); // 429
});

test.afterAll(async () => {
    cleanupF3();
});

// ==================== A. 单 API 统计 ====================

test('AS-1 单 API 统计：总量/成功率/今日/最近明细（异常高亮）/状态码三档', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        const stats = await pollApiStats(api, statsApiId, 5);

        expect(Number(stats.totalCalls)).toBe(5);
        expect(stats.successRate).toBe(0.8); // 4/5
        expect(Number(stats.todayCalls)).toBe(5);

        // 最近 5 条明细：最新一条是 429（异常高亮来源）
        expect(stats.recentLogs.length).toBe(5);
        expect(stats.recentLogs[0].statusCode).toBe(429);

        // 状态码三档：4 成功 / 1 客户端错误（429）/ 0 服务端错误（Long 序列化为字符串）
        expect(Number(stats.statusBreakdown.success)).toBe(4);
        expect(Number(stats.statusBreakdown.clientError)).toBe(1);
        expect(Number(stats.statusBreakdown.serverError)).toBe(0);

        // 调用方 Key 排行：2 个 Key（统计Key 3 次 + 统计限流Key 2 次）
        expect(stats.topKeys.length).toBe(2);
        expect(stats.topKeys.map((k: any) => k.name)).toContain(`${F3_PREFIX}统计Key`);
    } finally {
        await api.dispose();
    }
});

// ==================== B. 全局统计 ====================

test('AS-2 全局统计：overview/trend/health/top-apis/error-codes/top-keys/rate-limit-trend', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        // 等统计落地
        await pollApiStats(api, statsApiId, 5);

        // overview：总调用 >= 5，限流命中 >= 1
        const overview = await api.get('/data-service/stats/overview?range=24h');
        expect(Number(overview.totalCalls)).toBeGreaterThanOrEqual(5);
        expect(Number(overview.rateLimitedCount)).toBeGreaterThanOrEqual(1);
        expect(overview.successRate).toBeGreaterThan(0);

        // trend：有分桶（调用量 + 失败数）
        const trend = await api.get('/data-service/stats/trend?range=24h');
        expect(trend.length).toBeGreaterThan(0);
        expect(trend.some((t: any) => Number(t.total) >= 5)).toBe(true);

        // health-distribution：包含统计 API，错误率 0.2 ≥ 0.05 → SEVERE
        const health = await api.get('/data-service/stats/health-distribution?range=24h');
        const mine = health.items.find((i: any) => i.name === STATS_API_NAME);
        expect(mine).toBeTruthy();
        expect(mine.level).toBe('SEVERE');
        expect(Number(mine.totalCalls)).toBe(5);

        // top-apis：包含统计 API
        const topApis = await api.get('/data-service/stats/top-apis?range=24h&limit=10');
        expect(topApis.some((a: any) => a.name === STATS_API_NAME)).toBe(true);

        // error-codes：包含 429
        const errorCodes = await api.get('/data-service/stats/error-codes?range=24h&limit=10');
        expect(errorCodes.some((e: any) => e.statusCode === 429)).toBe(true);

        // top-keys：包含统计 Key（有调用，非僵尸）
        const topKeys = await api.get('/data-service/stats/top-keys?range=24h&limit=20');
        const mineKey = topKeys.find((k: any) => k.name === `${F3_PREFIX}统计Key`);
        expect(mineKey).toBeTruthy();
        expect(Number(mineKey.calls)).toBeGreaterThanOrEqual(3);
        expect(mineKey.zombie).toBe(false);

        // rate-limit-trend：有限流命中桶
        const rlt = await api.get('/data-service/stats/rate-limit-trend?range=24h');
        expect(rlt.some((t: any) => Number(t.total) >= 1)).toBe(true);
    } finally {
        await api.dispose();
    }
});

// ==================== C. 前端观测页 ====================

test('AS-3 前端：API 运行统计页渲染（KPI 4 卡 + 7 区块）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/data-service/api-stats');

    await expect(page.getByRole('heading', {name: 'API 运行统计'})).toBeVisible();

    // KPI 4 卡
    await expect(page.getByText('总调用量', {exact: true})).toBeVisible();
    await expect(page.getByText('平均成功率', {exact: true})).toBeVisible();
    await expect(page.getByText('P95 耗时', {exact: true})).toBeVisible();
    await expect(page.getByText('限流命中', {exact: true})).toBeVisible();

    // 各区块标题
    await expect(page.getByText('全局调用量趋势', {exact: true})).toBeVisible();
    await expect(page.getByText('API 健康分布', {exact: true})).toBeVisible();
    await expect(page.getByText('Top 5 API 调用排行', {exact: true})).toBeVisible();
    await expect(page.getByText('错误码分布', {exact: true})).toBeVisible();
    await expect(page.getByText('调用方 Key 排行', {exact: true})).toBeVisible();
    await expect(page.getByText('限流命中趋势', {exact: true})).toBeVisible();
    await expect(page.getByText('API 状态速览', {exact: true})).toBeVisible();
});

test('AS-4 前端：API 详情页调用统计区块（含 429 异常明细）', async ({page}) => {
    expect(statsApiId).toBeTruthy();
    await gotoAs(page, ADMIN.username, ADMIN.password, `/data-service/api-manage/${statsApiId}`);

    // 详情页标题 = API 名称
    await expect(page.getByRole('heading', {name: STATS_API_NAME})).toBeVisible();

    // 调用统计区块
    await expect(page.getByText('调用统计', {exact: true})).toBeVisible();
    await expect(page.getByText('最近调用', {exact: true})).toBeVisible();
    // 最近调用明细出现 429（异常高亮，数据端到端打通）
    await expect(page.getByText('429', {exact: true}).first()).toBeVisible();
});
