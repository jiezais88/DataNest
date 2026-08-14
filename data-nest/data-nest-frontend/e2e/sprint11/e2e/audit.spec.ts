import {expect, type Page, test} from '@playwright/test';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    MYSQL_DS_ID,
    PREFIX,
    SENS,
    SQL_OK,
    cleanupAudit,
    ensureAnalyst,
    getSensitivity,
    seedAudit,
    setSensitivity,
    snapshotSensitivity,
} from './helpers/audit-seed';

/**
 * Sprint 11 F1 E2E：审计日志（8 类操作埋点 + 查询页全功能 + 权限 + 只增不改）。
 *
 * 覆盖验收点（对齐 PRD §8 验收标准 AL-1 ~ AL-10 + 技术文档 §5.2）：
 * - AL-1 创建用户 → 审计 CREATE/USER
 * - AL-2 SQL 执行成功 → 审计 EXECUTE/SQL_QUERY SUCCESS（内容含行数/耗时）
 * - AL-3 SQL 命中机密表被拦截 → 审计 EXECUTE/SQL_QUERY FAILURE（失败原因含「机密」）
 * - AL-4 改级 → 审计 CHANGE_LEVEL/SENSITIVITY（内容 旧→新）
 * - AL-5 按操作人筛选 / AL-6 按操作类型筛选 / AL-7 时间范围筛选
 * - AL-8 详情抽屉（SQL 类完整 SQL 文本）
 * - AL-9 分析师无权限（菜单隐藏 + API 403）
 * - AL-10 只增不改：无修改/删除接口
 * - 8 类埋点：USER / DATASOURCE / SYNC_JOB / DAG / SQL_QUERY / DATA_API / API_KEY / SENSITIVITY
 *
 * 策略：操作埋点用 API 触发（快速、跨 5 个服务），审计写入是异步的（executor + Feign），
 * 触发后用审计 API 轮询等待落库（waitForAudit）——这是「API 辅助诊断」；
 * 查询页 UI 交互（列表/筛选/详情/分页/高亮/重置）全部 Playwright 真浏览器验证（UI E2E 为主）。
 *
 * 环境约定：
 * - 前端 http://localhost:3000（nginx 代理 /api → gateway http://localhost:8080）
 * - 数据自播种自清理（helpers/audit-seed.ts），前缀 e2e_s11_；测试表 datanest.target_users
 *   临时改级造机密拦截数据，测完恢复 PUBLIC（不动 target_products，避免下线线上 PUBLISHED API）。
 */

test.describe.configure({mode: 'serial'});

// ==================== 共享状态（串行用例间传递） ====================

const TS = Date.now();
const uid = () => `${PREFIX}${TS}_${Math.random().toString(36).slice(2, 6)}`;

let adminApi: Api;           // admin 客户端（播种后创建）
let analystId = '';          // 临时分析师 userId
let origLevel = 'PUBLIC';    // target_users 原始敏感度快照

// 各埋点用例产生的资源 id（后续用例/清理复用）
let createdUserId = '';
let createdDsId = '';
let createdSyncJobId = '';
let createdDagId = '';
let createdApiId = '';
let createdKeyId = '';

// ==================== 小工具 ====================

async function sleep(ms: number): Promise<void> {
    await new Promise((r) => setTimeout(r, ms));
}

/** 审计查询（简化版，返回 records） */
async function queryAudit(api: Api, params: Record<string, string>): Promise<any[]> {
    const qs = new URLSearchParams({page: '1', pageSize: '100', ...params}).toString();
    const data = await api.get<{ records: any[] }>(`/system/audit-logs?${qs}`);
    return data.records;
}

/** 轮询等待审计记录落库（审计异步写入；跨服务 Feign + executor 延迟可达秒级） */
async function waitForAudit(
    api: Api,
    params: Record<string, string>,
    predicate: (r: any) => boolean,
    timeoutMs = 30_000,
): Promise<any> {
    const deadline = Date.now() + timeoutMs;
    let last: any[] = [];
    while (Date.now() < deadline) {
        last = await queryAudit(api, params);
        const hit = last.find(predicate);
        if (hit) return hit;
        await sleep(1200);
    }
    throw new Error(`审计记录超时未出现 params=${JSON.stringify(params)} 最近=${JSON.stringify(last.slice(0, 3))}`);
}

/** 审计页行定位（按资源文本） */
function row(page: Page, text: string) {
    return page.locator('.ant-table-row').filter({hasText: text});
}

// ==================== 播种 / 清理 ====================

test.beforeAll(async () => {
    await seedAudit();
    origLevel = snapshotSensitivity();
    adminApi = await Api.create();
    await adminApi.login(ADMIN.username, ADMIN.password);
    analystId = await ensureAnalyst(adminApi);
});

test.afterAll(async () => {
    try {
        await adminApi.dispose();
    } finally {
        await cleanupAudit();
    }
});

// ==================== A. 用户管理埋点（AL-1 + 启停 + 重置密码） ====================

test('A1 用户管理埋点：创建/启停/重置密码 → 审计 USER', async () => {
    const name = `e2e_s11_usr_${TS}`;
    // AL-1 创建用户
    const user = await adminApi.post<{ id: string }>('/system/users', {
        username: name,
        password: 'Test123456',
        roles: ['DATA_ENGINEER'],
        email: `${name}@test.io`,
    });
    createdUserId = String(user.id);

    await waitForAudit(adminApi, {resourceType: 'USER'}, (r) =>
        r.opType === 'CREATE' && r.resourceName === name);

    // 启停
    await adminApi.put(`/system/users/${createdUserId}/toggle`);
    await waitForAudit(adminApi, {resourceType: 'USER'}, (r) =>
        r.opType === 'UPDATE' && r.resourceId === createdUserId);

    // 重置密码
    await adminApi.put(`/system/users/${createdUserId}/reset-password`, {newPassword: 'NewPass123'});
    await waitForAudit(adminApi, {resourceType: 'USER'}, (r) =>
        r.opType === 'RESET_PASSWORD' && r.resourceId === createdUserId);
});

// ==================== B. 数据源埋点 ====================

test('B1 数据源埋点：创建/编辑/测试/删除 → 审计 DATASOURCE', async () => {
    const name = `e2e_s11_ds_${TS}`;
    const created = await adminApi.post<{ id: string }>('/engineering/datasources', {
        name,
        type: 'MYSQL',
        host: 'middleware-test-mysql',
        port: 3306,
        databaseName: 'testdb',
        username: 'root',
        password: 'root123',
        autoCollectOnSave: false,
    });
    createdDsId = String(created.id);

    await waitForAudit(adminApi, {resourceType: 'DATASOURCE'}, (r) =>
        r.opType === 'CREATE' && r.resourceName === name);

    // 编辑
    await adminApi.put(`/engineering/datasources/${createdDsId}`, {
        name,
        type: 'MYSQL',
        host: 'middleware-test-mysql',
        port: 3306,
        databaseName: 'testdb',
        username: 'root',
        passwordChanged: false,
        description: '审计埋点测试-编辑',
    });
    await waitForAudit(adminApi, {resourceType: 'DATASOURCE'}, (r) =>
        r.opType === 'UPDATE' && r.resourceId === createdDsId);

    // 测试连接
    await adminApi.post(`/engineering/datasources/${createdDsId}/test`);
    await waitForAudit(adminApi, {resourceType: 'DATASOURCE'}, (r) =>
        r.opType === 'TEST' && r.resourceId === createdDsId);

    // 删除
    await adminApi.del(`/engineering/datasources/${createdDsId}`);
    await waitForAudit(adminApi, {resourceType: 'DATASOURCE'}, (r) =>
        r.opType === 'DELETE' && r.resourceId === createdDsId);
});

// ==================== C. 同步任务埋点 ====================

/** 轮询同步任务执行历史直到不再 RUNNING（execute 后删除前置条件；源表不存在则快速失败，不写 Doris） */
async function waitSyncJobFinished(syncJobId: string, timeoutMs = 60_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const data = await adminApi.post<{ records: any[] }>(`/engineering/sync-jobs/${syncJobId}/history/page`, {
            page: 1,
            pageSize: 5,
        });
        const latest = data.records?.[0];
        if (latest && latest.status !== 'RUNNING') return;
        await sleep(1500);
    }
    throw new Error(`同步任务执行未结束: syncJobId=${syncJobId}`);
}

test('C1 同步任务埋点：创建/修改/执行/删除 → 审计 SYNC_JOB', async () => {
    const name = `e2e_s11_sync_${TS}`;
    const body = {
        name,
        sourceDatasourceId: MYSQL_DS_ID,
        sourceDatabase: 'testdb',
        // 源表不存在：create 不校验源表存在，worker 读取源表快速失败，不写 Doris，副作用最小
        sourceTables: ['e2e_s11_no_such_table'],
        syncMode: 'FULL',
        triggerType: 'MANUAL',
        targetDatabase: 'datanest',
        targetTable: `e2e_s11_audit_tgt_${TS}`,
    };
    const created = await adminApi.post<{ id: string }>('/engineering/sync-jobs', body);
    createdSyncJobId = String(created.id);

    await waitForAudit(adminApi, {resourceType: 'SYNC_JOB'}, (r) =>
        r.opType === 'CREATE' && r.resourceName === name);

    // 修改
    await adminApi.put(`/engineering/sync-jobs/${createdSyncJobId}`, {
        ...body,
        name: name + '_v2',
        description: '审计埋点测试-修改',
    });
    await waitForAudit(adminApi, {resourceType: 'SYNC_JOB'}, (r) =>
        r.opType === 'UPDATE' && r.resourceId === createdSyncJobId);

    // 执行（PowerJob 异步投递；无论执行成败，触发动作即记录 EXECUTE）
    await adminApi.post(`/engineering/sync-jobs/${createdSyncJobId}/execute`);
    await waitForAudit(adminApi, {resourceType: 'SYNC_JOB'}, (r) =>
        r.opType === 'EXECUTE' && r.resourceId === createdSyncJobId);

    // 等执行到达终态（否则删除被拒 6005 任务正在执行中）
    await waitSyncJobFinished(createdSyncJobId);

    // 删除
    await adminApi.del(`/engineering/sync-jobs/${createdSyncJobId}`);
    await waitForAudit(adminApi, {resourceType: 'SYNC_JOB'}, (r) =>
        r.opType === 'DELETE' && r.resourceId === createdSyncJobId);
});

// ==================== D. DAG 埋点 ====================

test('D1 DAG 埋点：创建/修改/触发/删除 → 审计 DAG', async () => {
    const name = `e2e_s11_dag_${TS}`;
    const node = {
        nodeId: 'n1',
        nodeName: '查询',
        nodeType: 'SQL',
        positionX: 0,
        positionY: 0,
        config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'}),
    };
    const payload = {
        projectId: '2083083277706489857', // 现有 test 项目
        name,
        triggerType: 'MANUAL',
        nodes: [node],
        edges: [],
    };
    const created = await adminApi.post<{ id: string }>('/engineering/dev/dags', payload);
    createdDagId = String(created.id);

    await waitForAudit(adminApi, {resourceType: 'DAG'}, (r) =>
        r.opType === 'CREATE' && r.resourceName === name);

    // 修改
    await adminApi.put(`/engineering/dev/dags/${createdDagId}`, {...payload, name: name + '_v2'});
    await waitForAudit(adminApi, {resourceType: 'DAG'}, (r) =>
        r.opType === 'UPDATE' && r.resourceId === createdDagId);

    // 手动触发（PowerJob 异步；无论执行成败，触发动作即记录 TRIGGER）
    await adminApi.post(`/engineering/dev/dags/${createdDagId}/trigger`, {});
    await waitForAudit(adminApi, {resourceType: 'DAG'}, (r) =>
        r.opType === 'TRIGGER' && r.resourceId === createdDagId);

    // 删除
    await adminApi.del(`/engineering/dev/dags/${createdDagId}`);
    await waitForAudit(adminApi, {resourceType: 'DAG'}, (r) =>
        r.opType === 'DELETE' && r.resourceId === createdDagId);
});

// ==================== E. SQL 成功执行埋点（AL-2） ====================

test('E1 SQL 成功执行 → 审计 EXECUTE/SQL_QUERY SUCCESS（含行数/耗时）', async () => {
    await adminApi.post('/data-service/sql-console/execute', {
        datasourceId: SQL_OK.datasourceId,
        sql: SQL_OK.sql,
        timeoutSeconds: 30,
    });
    const rec = await waitForAudit(adminApi, {resourceType: 'SQL_QUERY'}, (r) =>
        r.opType === 'EXECUTE' && r.result === 'SUCCESS' && (r.content ?? '').includes('行数:'));
    expect(rec.resourceName).toBe('Doris 数仓');
    expect(rec.content).toMatch(/\| 行数:3 \| 耗时:\d+ms/);
    expect(rec.operatorName).toBe(ADMIN.username);
});

// ==================== F. 改级埋点（AL-4）+ SQL 机密拦截（AL-3） ====================

test('F1 改级 → 审计 CHANGE_LEVEL/SENSITIVITY（内容 旧→新）', async () => {
    const before = getSensitivity();
    expect(before).toBe('PUBLIC'); // 预期初始 PUBLIC（seed 已复位）
    await adminApi.put(`/governance/metadata/tables/${SENS.tableId}/sensitivity`, {newLevel: 'CONFIDENTIAL'});
    const rec = await waitForAudit(adminApi, {resourceType: 'SENSITIVITY'}, (r) =>
        r.opType === 'CHANGE_LEVEL' && (r.content ?? '').includes('→'));
    expect(rec.content).toBe('PUBLIC→CONFIDENTIAL');
    expect(rec.resourceName).toBe(`${SENS.database}.${SENS.table}`);
    expect(rec.operatorName).toBe(ADMIN.username);
    expect(getSensitivity()).toBe('CONFIDENTIAL');
});

test('F2 SQL 命中机密表被拦截 → 审计 EXECUTE/SQL_QUERY FAILURE（失败原因含「机密」）', async () => {
    // 用分析师执行查询机密表（target_users 已 CONFIDENTIAL）
    const analystApi = await Api.create();
    await analystApi.login(`${PREFIX}analyst`, 'Test123456');
    const resp = await analystApi.raw('POST', '/data-service/sql-console/execute', {
        datasourceId: -1,
        sql: `SELECT * FROM ${SENS.database}.${SENS.table} LIMIT 100;`,
        timeoutSeconds: 30,
    });
    expect(resp.code).toBe(9004); // TABLE_SENSITIVE
    await analystApi.dispose();

    const rec = await waitForAudit(adminApi, {resourceType: 'SQL_QUERY'}, (r) =>
        r.opType === 'EXECUTE' && r.result === 'FAILURE' && (r.errorMessage ?? '').includes('机密'));
    expect(rec.operatorName).toBe(`${PREFIX}analyst`);
    expect(rec.errorMessage).toContain(SENS.table);
});

// 机密拦截数据用完后恢复原始级别（用户确认：测完恢复）
test('F3 恢复 target_users 敏感度（清理快照）', async () => {
    if (getSensitivity() !== origLevel) {
        setSensitivity(origLevel as 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL');
    }
    expect(getSensitivity()).toBe(origLevel);
});

// ==================== G. 数据 API 埋点 ====================

test('G1 数据 API 埋点：创建/修改/发布/下线/删除 → 审计 DATA_API', async () => {
    const name = `e2e_s11_api_${TS}`;
    const body = {
        name,
        path: `e2e-s11-audit-${TS}`,
        datasourceId: -1,
        databaseName: 'ods',
        tableName: 'users',
        metadataTableId: '2083098679296512002', // ods.users（PUBLIC）
        paginated: 1,
    };
    const created = await adminApi.post<{ id: string }>('/data-service/apis', body);
    createdApiId = String(created.id);

    await waitForAudit(adminApi, {resourceType: 'DATA_API'}, (r) =>
        r.opType === 'CREATE' && r.resourceName === name);

    // 修改
    await adminApi.put(`/data-service/apis/${createdApiId}`, {...body, name: name + '_v2'});
    await waitForAudit(adminApi, {resourceType: 'DATA_API'}, (r) =>
        r.opType === 'UPDATE' && r.resourceId === createdApiId);

    // 发布
    await adminApi.post(`/data-service/apis/${createdApiId}/publish`);
    await waitForAudit(adminApi, {resourceType: 'DATA_API'}, (r) =>
        r.opType === 'PUBLISH' && r.resourceId === createdApiId);

    // 下线
    await adminApi.post(`/data-service/apis/${createdApiId}/disable`);
    await waitForAudit(adminApi, {resourceType: 'DATA_API'}, (r) =>
        r.opType === 'OFFLINE' && r.resourceId === createdApiId);

    // 删除
    await adminApi.del(`/data-service/apis/${createdApiId}`);
    await waitForAudit(adminApi, {resourceType: 'DATA_API'}, (r) =>
        r.opType === 'DELETE' && r.resourceId === createdApiId);
});

// ==================== H. API Key 埋点 ====================

test('H1 API Key 埋点：创建/修改/禁用/启用/删除 → 审计 API_KEY', async () => {
    const name = `e2e_s11_key_${TS}`;
    const created = await adminApi.post<{ id: string }>('/data-service/api-keys', {
        name,
        qpsLimit: 100,
    });
    createdKeyId = String(created.id);

    await waitForAudit(adminApi, {resourceType: 'API_KEY'}, (r) =>
        r.opType === 'CREATE' && r.resourceName === name);

    // 修改
    await adminApi.put(`/data-service/api-keys/${createdKeyId}`, {name: name + '_v2', qpsLimit: 200});
    await waitForAudit(adminApi, {resourceType: 'API_KEY'}, (r) =>
        r.opType === 'UPDATE' && r.resourceId === createdKeyId);

    // 禁用
    await adminApi.post(`/data-service/api-keys/${createdKeyId}/disable`);
    await waitForAudit(adminApi, {resourceType: 'API_KEY'}, (r) =>
        r.opType === 'DISABLE' && r.resourceId === createdKeyId);

    // 启用
    await adminApi.post(`/data-service/api-keys/${createdKeyId}/enable`);
    await waitForAudit(adminApi, {resourceType: 'API_KEY'}, (r) =>
        r.opType === 'ENABLE' && r.resourceId === createdKeyId);

    // 删除
    await adminApi.del(`/data-service/api-keys/${createdKeyId}`);
    await waitForAudit(adminApi, {resourceType: 'API_KEY'}, (r) =>
        r.opType === 'DELETE' && r.resourceId === createdKeyId);
});

// ==================== I. 审计查询页 UI（列表/展示） ====================

test('I1 审计页加载：标题/描述/筛选区/表格列头齐全', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    await expect(page.getByRole('heading', {name: '审计日志'})).toBeVisible();
    await expect(page.getByText('追踪平台关键操作留痕，支持按操作人、类型、时间范围与关键词检索')).toBeVisible();

    // 筛选区：操作人 / 操作类型 / 资源类型 / 时间范围 / 关键词 + 查询/重置
    await expect(page.getByPlaceholder('操作人...')).toBeVisible();
    await expect(page.getByLabel('按操作类型筛选')).toBeVisible();
    await expect(page.getByLabel('按资源类型筛选')).toBeVisible();
    await expect(page.getByRole('button', {name: '查询', exact: true})).toBeVisible();
    await expect(page.getByRole('button', {name: '重置', exact: true})).toBeVisible();

    // 表格列头
    for (const header of ['操作时间', '操作人', '操作类型', '资源', '内容摘要', '结果', 'IP']) {
        await expect(page.getByRole('columnheader', {name: header})).toBeVisible();
    }
});

test('I2 列表展示：默认近 7 天记录渲染（资源列类型:名称 + 结果徽章）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 等待表格出现数据（本套件产生的操作在默认近 7 天内）
    await expect(page.locator('.ant-table-row').first()).toBeVisible();

    // 任一资源列格式「类型: 名称」（如「用户: xxx」）
    const anyResource = page.locator('.ant-table-row').filter({hasText: ': '}).first();
    await expect(anyResource).toBeVisible();

    // 结果徽章：成功 / 失败 至少其一出现
    await expect(
        page.locator('.ant-table-row').filter({hasText: '成功'}).first(),
    ).toBeVisible();
});

// ==================== J. 筛选功能（AL-5 / AL-6 / AL-7 / 资源类型 / 关键词 / 组合 / 重置） ====================

/** 等待表格 loading 指示消失（查询完成、新数据已渲染），再读取行数避免旧表格竞态 */
async function waitTableSettled(page: Page): Promise<void> {
    // antd Table loading 时出现 .ant-spin-spinning；消失即代表查询返回并完成渲染
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, {timeout: 15_000});
    await expect(page.locator('.ant-table-row').first()).toBeVisible();
}

/** 筛选后等待表格重载完成：第一行满足预期文本（auto-retry），避免 count() 读到旧表格 */
async function waitFilteredRows(page: Page, firstRowContains: string): Promise<void> {
    await waitTableSettled(page);
    const rows = page.locator('.ant-table-row');
    await expect(rows.first()).toContainText(firstRowContains);
}

test('J1 按操作人筛选（AL-5）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    await page.getByPlaceholder('操作人...').fill(ADMIN.username);
    await page.getByRole('button', {name: '查询', exact: true}).click();
    // 等待第一行出现 admin（新数据渲染完成）再遍历
    await waitFilteredRows(page, ADMIN.username);
    const rows = page.locator('.ant-table-row');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    for (let i = 0; i < count; i++) {
        await expect(rows.nth(i)).toContainText(ADMIN.username);
    }
});

test('J2 按操作类型筛选（AL-6）：选「创建」只显示创建记录', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    await page.getByLabel('按操作类型筛选').selectOption('CREATE');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await waitFilteredRows(page, '创建');
    const rows = page.locator('.ant-table-row');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    for (let i = 0; i < count; i++) {
        await expect(rows.nth(i)).toContainText('创建');
    }
});

test('J3 按资源类型筛选：选「用户」只显示用户资源', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    await page.getByLabel('按资源类型筛选').selectOption('USER');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await waitFilteredRows(page, '用户:');
    const rows = page.locator('.ant-table-row');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    for (let i = 0; i < count; i++) {
        await expect(rows.nth(i)).toContainText('用户:');
    }
});

test('J4 关键词筛选：匹配 SQL 内容摘要（SELECT ods.users）', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    // 关键词匹配 resourceName/content：SQL 成功执行的内容含 SELECT 语句（E1 产生）
    await page.getByPlaceholder('关键词（资源/内容）...').fill('SELECT * FROM ods.users');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    const rows = page.locator('.ant-table-row');
    await expect(rows.first()).toBeVisible();
    // 至少能匹配到该 SQL 记录
    await expect(row(page, 'SELECT * FROM ods.users').first()).toBeVisible();
});

test('J5 组合筛选：操作人 admin + 操作类型 SQL 查询', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    await page.getByPlaceholder('操作人...').fill(ADMIN.username);
    await page.getByLabel('按操作类型筛选').selectOption('EXECUTE');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await waitTableSettled(page);
    const rows = page.locator('.ant-table-row');
    const count = await rows.count();
    expect(count).toBeGreaterThan(0);
    for (let i = 0; i < count; i++) {
        await expect(rows.nth(i)).toContainText(ADMIN.username);
        await expect(rows.nth(i)).toContainText('执行');
    }
});

test('J6 时间范围筛选（AL-7）：改为「今天」仍能看到记录；改为过去范围为空', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');

    // 定位 RangePicker 输入框（antd 时间范围选择器，两个 input）
    const rangeInputs = page.locator('.ant-picker-range input');

    // 方案：直接给输入框赋值并回车触发（showTime 格式 YYYY-MM-DD HH:mm）
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const todayStart = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} 00:00`;
    const todayEnd = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} 23:59`;

    await rangeInputs.nth(0).fill(todayStart);
    await rangeInputs.nth(0).press('Enter');
    await rangeInputs.nth(1).fill(todayEnd);
    await rangeInputs.nth(1).press('Enter');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    // 今天应有本测试播种的记录
    await expect(page.locator('.ant-table-row').first()).toBeVisible();

    // 过去范围（2020-01-01 ~ 2020-01-02）→ 空态
    await rangeInputs.nth(0).fill('2020-01-01 00:00');
    await rangeInputs.nth(0).press('Enter');
    await rangeInputs.nth(1).fill('2020-01-02 23:59');
    await rangeInputs.nth(1).press('Enter');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(page.getByText('暂无审计记录')).toBeVisible();
});

test('J7 重置：清空筛选恢复默认近 7 天', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    // 先设一个会过滤空的组合
    await page.getByPlaceholder('操作人...').fill('__不存在的用户__');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    await expect(page.getByText('暂无审计记录')).toBeVisible();
    // 重置 → 恢复默认
    await page.getByRole('button', {name: '重置'}).click();
    await expect(page.locator('.ant-table-row').first()).toBeVisible();
});

// ==================== K. 详情抽屉（AL-8） ====================

test('K1 详情抽屉：点击 SQL 记录展示完整 SQL 文本', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    // 按 SQL 查询类型筛选出 SQL 记录
    await page.getByLabel('按资源类型筛选').selectOption('SQL_QUERY');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    const sqlRow = page.locator('.ant-table-row').filter({hasText: 'SQL 查询'}).first();
    await expect(sqlRow).toBeVisible();
    await sqlRow.click();

    // 抽屉打开（自定义 Drawer：role=dialog）
    const drawer = page.getByRole('dialog').first();
    await expect(page.getByRole('heading', {name: '审计详情'})).toBeVisible();
    await expect(drawer.getByText('执行 SQL', {exact: true})).toBeVisible();
    // SQL 内容块：pre 含完整 SQL + 行数/耗时（列表页 ellipsis 截断、详情完整展示）
    const sqlPre = drawer.locator('pre').first();
    await expect(sqlPre).toBeVisible();
    await expect(sqlPre).toContainText('SELECT');
    await expect(sqlPre).toContainText('行数:');
    // 全字段展示：操作人 / 操作类型 / 资源类型 / 资源名称 / 客户端 IP / 执行结果
    await expect(drawer.getByText('操作人')).toBeVisible();
    await expect(drawer.getByText('操作类型')).toBeVisible();
    await expect(drawer.getByText('资源类型')).toBeVisible();
    await expect(drawer.getByText('资源名称')).toBeVisible();
    await expect(drawer.getByText('客户端 IP')).toBeVisible();
    await expect(drawer.getByText('执行结果')).toBeVisible();
});

// ==================== L. 失败行高亮 + 分页 ====================

test('L1 失败行浅红高亮：机密拦截 FAILURE 行有 bg-ds-danger-light', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    // 机密拦截失败记录：分析师执行 SQL 机密表（F2 产生）
    await page.getByLabel('按资源类型筛选').selectOption('SQL_QUERY');
    await page.getByRole('button', {name: '查询', exact: true}).click();
    const failRow = page.locator('.ant-table-row').filter({hasText: '失败'}).first();
    await expect(failRow).toBeVisible();
    await expect(failRow).toHaveClass(/bg-ds-danger-light/);
});

test('L2 分页：默认每页 10 条，多页时显示「共 X 条」与翻页', async ({page}) => {
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    // 默认近 7 天应已有多条（本套件产生 30+ 条）
    await expect(page.locator('.ant-table-row').first()).toBeVisible();
    await expect(page.getByText(/共 \d+ 条/)).toBeVisible();
    // 若总条数 > 10，出现「下一页」
    const totalText = await page.getByText(/共 \d+ 条/).textContent();
    const total = Number((totalText ?? '').match(/共 (\d+) 条/)?.[1] ?? 0);
    if (total > 10) {
        const next = page.getByRole('button', {name: '下一页'});
        await expect(next).toBeVisible();
        await next.click();
        await expect(page.locator('.ant-table-row').first()).toBeVisible();
    }
});

// ==================== M. 权限与只增不改（AL-9 / AL-10） ====================

test('M1 权限（AL-9）：分析师访问审计页 → 菜单隐藏 + API 403', async ({page}) => {
    // 1) 侧边栏无「审计日志」菜单
    await gotoAs(page, `${PREFIX}analyst`, 'Test123456', '/');
    await expect(page.getByRole('heading', {name: '首页'}).or(page.getByText('欢迎')).first()).toBeVisible().catch(() => {});
    await expect(page.getByText('审计日志', {exact: true})).toHaveCount(0);

    // 2) 直接调审计 API → 403（code != 200）
    const analystApi = await Api.create();
    await analystApi.login(`${PREFIX}analyst`, 'Test123456');
    const resp = await analystApi.raw('GET', '/system/audit-logs?page=1&pageSize=10');
    expect(resp.code).not.toBe(200);
    await analystApi.dispose();
});

test('M2 只增不改（AL-10）：审计接口无修改/删除端点（PUT/DELETE → 405/404）', async ({page}) => {
    // 前端：查询页无删除/编辑按钮（仅列表 + 详情抽屉）
    await gotoAs(page, ADMIN.username, ADMIN.password, '/system/audit-logs');
    await expect(page.getByRole('button', {name: /删除/})).toHaveCount(0);

    // 后端：对审计资源执行 PUT/DELETE 应非 200（无修改/删除语义）
    const badPut = await adminApi.raw('PUT', '/system/audit-logs/some-id', {foo: 'bar'});
    expect(badPut.code).not.toBe(200);
    const badDel = await adminApi.raw('DELETE', '/system/audit-logs/some-id');
    expect(badDel.code).not.toBe(200);
});
