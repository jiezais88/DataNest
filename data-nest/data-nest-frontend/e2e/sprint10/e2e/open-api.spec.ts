import {expect, test} from '@playwright/test';
import {TARGET, getTargetTableId} from './helpers/f2-seed';
import {
    F3_PREFIX,
    F3_PATH_PREFIX,
    openApiCall,
    createApi,
    createKey,
    adminAction,
    cleanupF3,
} from './helpers/f3-seed';

/**
 * Sprint 10 F3 E2E：对外数据 API 网关（认证 / 参数化执行 / 限流 / 熔断）。
 *
 * 覆盖验收点（对齐 PRD §6.3/§6.4 §9.1 + 技术文档 §4.2 D-D3/D-D4）：
 * - AC-6 Key 认证：无/错/禁用/未绑定 Key 401；API 不存在 404；未发布/下线 404；正确 Key 200
 * - 参数化执行：EQ 等值 / RANGE 范围 / orderBy 排序 / 分页 page+pageSize+total / fields 字段裁剪 / pageSize 上限 clamp
 * - AC-7 限流：Key QPS=1 第 2 次 429 + Retry-After；窗口恢复后可再调用
 * - AC-8 熔断：数据源（Doris=-1 维度）连续失败 → 503；半开探测通过自动闭合
 *
 * 环境约定：
 * - 对外调用经网关 http://localhost:8080/api/data-service/open-api/v1/{path}（网关已放行登录态）
 * - 主测试表：内置 Doris datanest.target_products（10 行：手机 6 / 电脑 2 / 耳机 2，id 1~5 各重复 2 行）
 * - 熔断触发：指向不存在表 e2e_no_such_table_xyz 的 API（SQL 执行失败 → recordFailure）
 * - 串行执行；熔断用例放最后（开闸影响同数据源 Doris 其它 API 30s）
 */

test.describe.configure({mode: 'serial'});

// ==================== 共享状态 ====================
let ordersApiId = '';       // 订单 API（参数化主链路）
let unpublishedApiId = '';  // 未发布 API
let disabledApiId = '';     // 下线 API
let breakerApiId = '';      // 熔断 API（坏表）
let smallApiId = '';        // 小页 API（pageSizeMax=3）
let normalKey = '';         // 正常 Key（QPS 1000，绑定全部 API）
let rateLimitKey = '';      // 限流 Key（QPS=1）
let disabledKey = '';       // 禁用 Key（明文）
let unboundKey = '';        // 未绑定 Key（明文）

const CATEGORY_PHONE = '手机';
const ORDERS_PATH = `${F3_PATH_PREFIX}orders`;

// ==================== 播种/清理 ====================

test.beforeAll(async () => {
    cleanupF3();
    const base = {
        datasourceId: TARGET.datasourceId,
        databaseName: TARGET.databaseName,
        tableName: TARGET.tableName,
        metadataTableId: getTargetTableId(),
    };

    // 1. 订单 API（参数化主链路：EQ + RANGE + 字段裁剪 + 排序 + 分页）
    const orders = await createApi({
        ...base,
        name: `${F3_PREFIX}订单API`,
        path: ORDERS_PATH,
        filters: [{field: 'category', type: 'EQ'}, {field: 'price', type: 'RANGE'}],
        fields: ['id', 'name', 'price', 'stock', 'category', 'status'],
        orderBy: 'price DESC',
        paginated: 1,
        pageSizeMax: 50,
    });
    ordersApiId = orders.id;
    await adminAction('POST', `/data-service/apis/${ordersApiId}/publish`);

    // 2. 未发布 API（CREATED）
    const unpub = await createApi({
        ...base, name: `${F3_PREFIX}未发布API`, path: `${F3_PATH_PREFIX}unpub`, paginated: 1,
    });
    unpublishedApiId = unpub.id;

    // 3. 下线 API（PUBLISHED → DISABLED）
    const dis = await createApi({
        ...base, name: `${F3_PREFIX}下线API`, path: `${F3_PATH_PREFIX}disabled`, paginated: 1,
    });
    disabledApiId = dis.id;
    await adminAction('POST', `/data-service/apis/${disabledApiId}/publish`);
    await adminAction('POST', `/data-service/apis/${disabledApiId}/disable`);

    // 4. 熔断 API（坏表，SQL 执行失败）
    const breaker = await createApi({
        datasourceId: TARGET.datasourceId,
        databaseName: TARGET.databaseName,
        tableName: 'e2e_no_such_table_xyz',
        name: `${F3_PREFIX}熔断API`,
        path: `${F3_PATH_PREFIX}breaker`,
        paginated: 1,
    });
    breakerApiId = breaker.id;
    await adminAction('POST', `/data-service/apis/${breakerApiId}/publish`);

    // 5. 小页 API（pageSizeMax=3 测 clamp）
    const small = await createApi({
        ...base, name: `${F3_PREFIX}小页API`, path: `${F3_PATH_PREFIX}small`, paginated: 1, pageSizeMax: 3,
    });
    smallApiId = small.id;
    await adminAction('POST', `/data-service/apis/${smallApiId}/publish`);

    // 6. Keys（normalKey 绑定全部 5 个 API，用于 404 生命周期 + 熔断 + 成功链路）
    const nk = await createKey({
        name: `${F3_PREFIX}正常Key`, qpsLimit: 1000,
        apiIds: [ordersApiId, unpublishedApiId, disabledApiId, breakerApiId, smallApiId],
    });
    normalKey = nk.apiKey;

    const rk = await createKey({name: `${F3_PREFIX}限流Key`, qpsLimit: 1, apiIds: [ordersApiId]});
    rateLimitKey = rk.apiKey;

    const dk = await createKey({name: `${F3_PREFIX}禁用Key`, qpsLimit: 100, apiIds: [ordersApiId]});
    disabledKey = dk.apiKey;
    await adminAction('POST', `/data-service/api-keys/${dk.id}/disable`);

    const uk = await createKey({name: `${F3_PREFIX}未绑定Key`, qpsLimit: 100, apiIds: []});
    unboundKey = uk.apiKey;
});

test.afterAll(async () => {
    cleanupF3();
});

// ==================== A. Key 认证与生命周期 ====================

test('OA-1 认证：无 Key → 401（9005）', async () => {
    const r = await openApiCall(ORDERS_PATH);
    expect(r.status).toBe(401);
    expect(r.body?.code).toBe(9005);
});

test('OA-2 认证：错误 Key → 401（9005）', async () => {
    const r = await openApiCall(ORDERS_PATH, 'K-00000000000000000000000000000000');
    expect(r.status).toBe(401);
    expect(r.body?.code).toBe(9005);
});

test('OA-3 认证：禁用 Key → 401（9005）', async () => {
    const r = await openApiCall(ORDERS_PATH, disabledKey);
    expect(r.status).toBe(401);
    expect(r.body?.code).toBe(9005);
});

test('OA-4 认证：未绑定该 API 的 Key → 401（9005）', async () => {
    const r = await openApiCall(ORDERS_PATH, unboundKey);
    expect(r.status).toBe(401);
    expect(r.body?.code).toBe(9005);
});

test('OA-5 生命周期：API 路径不存在 → 404（9008）', async () => {
    const r = await openApiCall(`${F3_PATH_PREFIX}no-such-path`, normalKey);
    expect(r.status).toBe(404);
    expect(r.body?.code).toBe(9008);
});

test('OA-6 生命周期：未发布 API → 404（9007）', async () => {
    const r = await openApiCall(`${F3_PATH_PREFIX}unpub`, normalKey);
    expect(r.status).toBe(404);
    expect(r.body?.code).toBe(9007);
});

test('OA-7 生命周期：下线 API → 404（9007）', async () => {
    const r = await openApiCall(`${F3_PATH_PREFIX}disabled`, normalKey);
    expect(r.status).toBe(404);
    expect(r.body?.code).toBe(9007);
});

// ==================== B. 参数化执行 ====================

test('OA-8 执行：正确调用 → 200 + records/total + 排序 + 字段裁剪', async () => {
    const r = await openApiCall(ORDERS_PATH, normalKey);
    expect(r.status).toBe(200);
    expect(r.body?.code).toBe(200);
    const data = r.body!.data;
    expect(Number(data.total)).toBe(10);
    expect(data.records.length).toBe(10); // 10 行 < pageSize 20
    // orderBy price DESC → 首行 MacBook Pro（14999）
    expect(data.records[0].name).toBe('MacBook Pro');
    expect(Number(data.records[0].price)).toBe(14999);
    // fields 裁剪：无 created_at/updated_at
    expect(data.records[0]).not.toHaveProperty('created_at');
    expect(data.records[0]).not.toHaveProperty('updated_at');
});

test('OA-9 执行：EQ 等值筛选生效', async () => {
    const r = await openApiCall(ORDERS_PATH, normalKey, {category: CATEGORY_PHONE});
    expect(r.status).toBe(200);
    const data = r.body!.data;
    expect(Number(data.total)).toBe(6);
    expect(data.records.length).toBe(6);
    for (const rec of data.records) expect(rec.category).toBe(CATEGORY_PHONE);
});

test('OA-10 执行：RANGE 范围筛选生效（min_price/max_price）', async () => {
    const r = await openApiCall(ORDERS_PATH, normalKey, {min_price: '4000', max_price: '7000'});
    expect(r.status).toBe(200);
    const data = r.body!.data;
    expect(Number(data.total)).toBe(4); // 6999×2 + 5999×2
    for (const rec of data.records) {
        expect(Number(rec.price)).toBeGreaterThanOrEqual(4000);
        expect(Number(rec.price)).toBeLessThanOrEqual(7000);
    }
});

test('OA-11 执行：排序 orderBy（price DESC）生效', async () => {
    const r = await openApiCall(ORDERS_PATH, normalKey);
    const prices = r.body!.data.records.map((x: any) => Number(x.price));
    const sorted = [...prices].sort((a, b) => b - a);
    expect(prices).toEqual(sorted);
    expect(prices[0]).toBe(14999);
});

test('OA-12 执行：分页 page/pageSize 生效 + total', async () => {
    const page2 = await openApiCall(ORDERS_PATH, normalKey, {page: '2', pageSize: '3'});
    expect(page2.status).toBe(200);
    const data = page2.body!.data;
    expect(Number(data.total)).toBe(10);
    expect(data.records.length).toBe(3);
    // 第二页首行 = 全量排序后第 4 条（offset 3）
    const all = await openApiCall(ORDERS_PATH, normalKey, {pageSize: '50'});
    expect(data.records[0].id).toBe(all.body!.data.records[3].id);
});

test('OA-13 执行：返回字段裁剪（fields）生效', async () => {
    const r = await openApiCall(ORDERS_PATH, normalKey, {pageSize: '1'});
    const rec = r.body!.data.records[0];
    expect(rec).toHaveProperty('name');
    expect(rec).toHaveProperty('price');
    expect(rec).toHaveProperty('stock');
    expect(rec).not.toHaveProperty('created_at');
    expect(rec).not.toHaveProperty('updated_at');
});

test('OA-14 执行：pageSize 超上限 clamp 到 pageSizeMax', async () => {
    const r = await openApiCall(`${F3_PATH_PREFIX}small`, normalKey, {pageSize: '100'});
    expect(r.status).toBe(200);
    const data = r.body!.data;
    expect(Number(data.total)).toBe(10);
    expect(data.records.length).toBe(3); // clamp 到 pageSizeMax=3
});

// ==================== C. 限流 ====================

test('OA-15 限流：QPS=1 第 2 次调用 → 429 + Retry-After', async () => {
    const first = await openApiCall(ORDERS_PATH, rateLimitKey, {pageSize: '1'});
    expect(first.status).toBe(200);

    const second = await openApiCall(ORDERS_PATH, rateLimitKey, {pageSize: '1'});
    expect(second.status).toBe(429);
    expect(second.body?.code).toBe(9006);
    expect(second.retryAfter).toBeTruthy(); // Retry-After 头存在（= 窗口 60s）
});

test('OA-16 限流：窗口过期后可再调用（恢复）', async () => {
    // 上一用例第 1 次成功调用写入窗口成员（60s 滑动窗口），等待窗口过期
    await new Promise(r => setTimeout(r, 63_000));
    const r = await openApiCall(ORDERS_PATH, rateLimitKey, {pageSize: '1'});
    expect(r.status).toBe(200);
});

// ==================== D. 熔断 ====================

test('OA-17 熔断：数据源连续失败 → 开闸 503，且同数据源其它 API 一并 503', async () => {
    // 熔断器内存态按数据源维度（Doris=-1），failure-threshold=5。
    // 从闭合态连续调用坏表 API：开闸前每次 500（SQL 执行失败），达到阈值后 503。
    // 上限 10 次，适配历史残留失败（熔断器自愈/容器重启后从闭合态开始）。
    const statuses: number[] = [];
    for (let i = 0; i < 10; i++) {
        const r = await openApiCall(`${F3_PATH_PREFIX}breaker`, normalKey);
        statuses.push(r.status);
        if (r.status === 503) break;
    }
    const first503 = statuses.indexOf(503);
    expect(first503).toBeGreaterThan(0); // 开闸前至少有一次失败记录
    for (const s of statuses.slice(0, first503)) expect(s).toBe(500); // 开闸前均为 500
    expect(statuses[first503]).toBe(503);

    // 熔断是数据源维度（Doris=-1）：正常订单 API 也返回 503
    const orders = await openApiCall(ORDERS_PATH, normalKey, {pageSize: '1'});
    expect(orders.status).toBe(503);
    expect(orders.body?.code).toBe(9015);
});

test('OA-18 熔断：半开探测通过自动闭合（恢复）', async () => {
    // 等待 30s（circuitbreaker.wait-seconds）自动进入 HALF_OPEN
    await new Promise(r => setTimeout(r, 35_000));

    // 半开探测：正常 API 成功 → 闭合
    const probe = await openApiCall(ORDERS_PATH, normalKey, {pageSize: '1'});
    expect(probe.status).toBe(200);

    // 闭合后再调仍成功
    const again = await openApiCall(ORDERS_PATH, normalKey, {pageSize: '1'});
    expect(again.status).toBe(200);
});
