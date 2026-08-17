import {expect, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    BUILTIN_DORIS_ID,
    EXTERNAL_DORIS_ID,
    EXPECTED_AGG,
    MAIN_SQL,
    ORDERS_META_ID,
    PATH_PREFIX,
    PREFIX,
    PW,
    cleanupS13,
    seedS13,
} from '../e2e/helpers/seed';

/**
 * Sprint 13 自定义 SQL API 级 E2E（不依赖前端）。
 *
 * 覆盖 PRD §9（AC-2/3/4/5/6/7/8 + N-2/3）+ 技术文档 §7（V1~V7）：
 * - 创建：JOIN/聚合 SQL 保存成功、involvedTables 落库
 * - 只读校验（AC-3/V1）：UPDATE/DELETE/DDL/多语句拒绝
 * - 参数一致性（9018）与注入防护（AC-4/V2）：:param 词法替换、字符串/注释内不误替换
 * - 安全闸门（AC-5/V3）：机密/内部未开白/数据权限 fail-closed 9019（创建 + 发布重查）
 * - 对外执行（AC-6）：Key 调用返回加工结果 + 分页/total/clamp/截断（N-2）
 * - 血缘（AC-7/V6）：lineage_record 落库
 * - 审计（N-3）：创建/编辑/发布/删除埋点
 * - 存量回归（AC-8/V7）：选表形态创建/调用不变
 *
 * 串行执行（共享环境 + 闸门改库造数）。错误码以 ErrorCode.java 为准：
 * 9001 非只读 / 9002 语法 / 9017 无表引用 / 9018 参数不一致 / 9019 表被闸门拒。
 */

test.describe.configure({mode: 'serial'});

let mainApiId = '';
let mainKey = '';

test.beforeAll(async () => {
    await seedS13();
});

test.afterAll(async () => {
    await cleanupS13();
});

// ==================== A. 创建与校验 ====================

test('CS-01 创建：JOIN+聚合 SQL 保存成功，involvedTables 2 张', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post('/data-service/apis', {
        name: `${PREFIX}区域汇总`, path: `${PATH_PREFIX}region-sum`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest', sqlText: MAIN_SQL,
        sqlParams: [{name: 'startDate', type: 'DATE', required: true}], paginated: 1, pageSizeMax: 100,
    });
    mainApiId = detail.id;
    expect(detail.queryType).toBe('CUSTOM_SQL');
    expect(detail.sqlText).toContain('JOIN');
    const involved = detail.involvedTables ?? [];
    expect(involved).toHaveLength(2);
    const tables = involved.map((t: any) => t.table).sort();
    expect(tables).toEqual(['e2e_s13_orders', 'e2e_s13_region']);
    await api.dispose();
});

test('CS-02 只读校验：UPDATE/DELETE/DDL/写多语句 → 拒绝（9001/9017）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const badSqls = [
        'UPDATE datanest.e2e_s13_orders SET amount=0',
        'DELETE FROM datanest.e2e_s13_orders',
        'DROP TABLE datanest.e2e_s13_orders',
        'SELECT 1; DELETE FROM datanest.e2e_s13_orders',
    ];
    for (let i = 0; i < badSqls.length; i++) {
        const env = await api.raw('POST', '/data-service/apis', {
            name: `${PREFIX}只读${i}`, path: `${PATH_PREFIX}readonly-${i}`, queryType: 'CUSTOM_SQL',
            datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest', sqlText: badSqls[i], sqlParams: [],
        });
        expect([9001, 9017]).toContain(env.code);
    }
    await api.dispose();
});

test('CS-03 只读校验：语法错误 → 9002', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const env = await api.raw('POST', '/data-service/apis', {
        name: `${PREFIX}语法错`, path: `${PATH_PREFIX}syntax`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest', sqlText: 'SELECT FROM WHERE', sqlParams: [],
    });
    expect(env.code).toBe(9002);
    await api.dispose();
});

test('CS-04 多语句 SELECT 拒绝（技术文档 §6.2 分号检测）→ 见末尾已知缺陷用例 CS-26', async () => {
    // 用例移至末尾「已知缺陷回归」段（当前实现放行，等 backend 修复后转正）
});

test('CS-05 参数一致：漏定义/多定义/非法类型 → 9018', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const cases = [
        {sqlText: 'SELECT * FROM datanest.e2e_s13_orders WHERE order_date >= :startDate', sqlParams: []},
        {sqlText: 'SELECT * FROM datanest.e2e_s13_orders', sqlParams: [{name: 'foo', type: 'LONG', required: false}]},
        {sqlText: 'SELECT * FROM datanest.e2e_s13_orders WHERE region_id = :rid', sqlParams: [{name: 'rid', type: 'XXX', required: true}]},
    ];
    for (let i = 0; i < cases.length; i++) {
        const env = await api.raw('POST', '/data-service/apis', {
            name: `${PREFIX}参数${i}`, path: `${PATH_PREFIX}param-${i}`, queryType: 'CUSTOM_SQL',
            datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
            sqlText: cases[i].sqlText, sqlParams: cases[i].sqlParams,
        });
        expect(env.code).toBe(9018);
    }
    await api.dispose();
});

test('CS-06 无表引用 → 9017', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const env = await api.raw('POST', '/data-service/apis', {
        name: `${PREFIX}无表`, path: `${PATH_PREFIX}no-table`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest', sqlText: 'SELECT 1 AS one', sqlParams: [],
    });
    expect(env.code).toBe(9017);
    await api.dispose();
});

// ==================== B. 安全闸门（AC-5/V3，fail-closed） ====================

test('CS-07 敏感度闸门：机密/内部未开白 → 9019；开白放行', async () => {
    const {setOrdersSensitivity} = await import('../e2e/helpers/seed');
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const body = () => ({
        name: `${PREFIX}闸门`, path: `${PATH_PREFIX}gate`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
        sqlText: 'SELECT r.region_name, SUM(o.amount) AS total FROM datanest.e2e_s13_orders o JOIN datanest.e2e_s13_region r ON o.region_id = r.region_id GROUP BY r.region_name',
        sqlParams: [],
    });
    try {
        setOrdersSensitivity('CONFIDENTIAL');
        let env = await api.raw('POST', '/data-service/apis', body());
        expect(env.code).toBe(9019);
        expect(env.message).toContain('e2e_s13_orders');
        setOrdersSensitivity('INTERNAL', 0);
        env = await api.raw('POST', '/data-service/apis', body());
        expect(env.code).toBe(9019);
        setOrdersSensitivity('INTERNAL', 1);
        const ok = await api.raw('POST', '/data-service/apis', body());
        expect(ok.code).toBe(200);
    } finally {
        setOrdersSensitivity('PUBLIC', 0);
        await api.dispose();
    }
});

test('CS-08 数据权限闸门：白名单缺一张涉及表 → 9019 整体拒；单表放行', async () => {
    // engineer 数据权限 WHITELIST 仅 demo_ecommerce.ods_orders
    const api = await Api.create();
    await api.login(`${PREFIX}engineer`, PW);
    const joinEnv = await api.raw('POST', '/data-service/apis', {
        name: `${PREFIX}越权JOIN`, path: `${PATH_PREFIX}forbidden-join`, queryType: 'CUSTOM_SQL',
        datasourceId: EXTERNAL_DORIS_ID, databaseName: 'demo_ecommerce',
        sqlText: 'SELECT o.id, u.name FROM demo_ecommerce.ods_orders o JOIN demo_ecommerce.ods_users u ON o.user_id = u.id WHERE o.id > :minId',
        sqlParams: [{name: 'minId', type: 'LONG', required: true}],
    });
    expect(joinEnv.code).toBe(9019);
    expect(joinEnv.message).toContain('ods_users');
    const single = await api.post('/data-service/apis', {
        name: `${PREFIX}白名单单表`, path: `${PATH_PREFIX}whitelist-single`, queryType: 'CUSTOM_SQL',
        datasourceId: EXTERNAL_DORIS_ID, databaseName: 'demo_ecommerce',
        sqlText: 'SELECT id, user_id, amount FROM demo_ecommerce.ods_orders WHERE id > :minId ORDER BY id',
        sqlParams: [{name: 'minId', type: 'LONG', required: true}], paginated: 0,
    });
    expect(single.queryType).toBe('CUSTOM_SQL');
    await api.dispose();
});

test('CS-09 发布重查闸门：建后表改机密 → 发布被拒 9019（fail-closed）', async () => {
    expect(mainApiId).toBeTruthy();
    const {setOrdersSensitivity} = await import('../e2e/helpers/seed');
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        await api.post(`/data-service/apis/${mainApiId}/disable`);
        setOrdersSensitivity('CONFIDENTIAL');
        const env = await api.raw('POST', `/data-service/apis/${mainApiId}/publish`);
        expect(env.code).toBe(9019);
    } finally {
        setOrdersSensitivity('PUBLIC', 0);
        await api.post(`/data-service/apis/${mainApiId}/publish`);
        await api.dispose();
    }
});

// ==================== C. 对外执行（AC-6/V2/V4/N-2） ====================

test('CS-10 发布 + Key 调用：返回加工结果，total 正确（ORDER BY 断言见末尾 CS-23）', async () => {
    expect(mainApiId).toBeTruthy();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    await api.post(`/data-service/apis/${mainApiId}/publish`);
    const key = await api.post('/data-service/api-keys', {
        name: `${PREFIX}主Key`, qpsLimit: 1000, apiIds: [mainApiId],
    });
    mainKey = key.apiKey;
    await api.dispose();

    const res = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum?startDate=2026-01-01&page=1&pageSize=10`, {
        headers: {'X-API-Key': mainKey},
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(Number(body.data.total)).toBe(3);
    expect(body.data.records).toHaveLength(3);
});

test('CS-11 分页：pageSize clamp（偏移断言见缺陷回归段）', async () => {
    const big = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum?startDate=2026-01-01&pageSize=9999`, {
        headers: {'X-API-Key': mainKey},
    }).then(r => r.json());
    expect(big.data.records.length).toBeLessThanOrEqual(100);
});

// ==================== E. 已知缺陷回归（backend 修复后转正） ====================
// 1) 分页包裹丢失 SQL 内 ORDER BY（Doris 子查询排序被优化掉，返回顺序错误）
// 2) 多语句 SELECT（SELECT;SELECT）未按技术文档 §6.2 拒绝
// 注意：本段必须位于 CS-17 编辑用例之前（CS-17 会把主 API SQL 改成单表）

test('CS-23 缺陷回归：分页调用保留 SQL 内 ORDER BY（total DESC → SOUTH 首行）', async () => {
    const res = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum?startDate=2026-01-01&page=1&pageSize=10`, {
        headers: {'X-API-Key': mainKey},
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    const names = body.data.records.map((r: any) => r.region_name);
    const totals = body.data.records.map((r: any) => Number(r.total));
    expect(names).toEqual(EXPECTED_AGG.map(e => e.region_name));
    expect(totals).toEqual(EXPECTED_AGG.map(e => e.total));
});

test('CS-24 缺陷回归：分页偏移按 SQL 排序正确（page1=SOUTH，page2=NORTH）', async () => {
    const page1 = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum?startDate=2026-01-01&page=1&pageSize=1`, {
        headers: {'X-API-Key': mainKey},
    }).then(r => r.json());
    expect(page1.data.records[0].region_name).toBe('SOUTH');
    const page2 = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum?startDate=2026-01-01&page=2&pageSize=1`, {
        headers: {'X-API-Key': mainKey},
    }).then(r => r.json());
    expect(page2.data.records[0].region_name).toBe('NORTH');
});

test('CS-25 缺陷回归：外部 orderBy 参数被忽略，仍按 SQL 内排序（D9）', async () => {
    const res = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum?startDate=2026-01-01&orderBy=total%20ASC`, {
        headers: {'X-API-Key': mainKey},
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.data.records[0].region_name).toBe('SOUTH');
});

test('CS-26 缺陷回归：多语句 SELECT 必须拒绝（技术文档 §6.2 分号检测）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const env = await api.raw('POST', '/data-service/apis', {
        name: `${PREFIX}多语句`, path: `${PATH_PREFIX}multi`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
        sqlText: 'SELECT order_id FROM datanest.e2e_s13_orders; SELECT region_name FROM datanest.e2e_s13_region',
        sqlParams: [],
    });
    expect([9001, 9017]).toContain(env.code);
    await api.dispose();
});

test('CS-12 必填参数缺失 → 400（9018）', async () => {
    const res = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}region-sum`, {
        headers: {'X-API-Key': mainKey},
    });
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe(9018);
});

test('CS-13 注入防护：STRING 参数含 SQL 片段被当字面值，无副作用', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post('/data-service/apis', {
        name: `${PREFIX}备注查询`, path: `${PATH_PREFIX}remark`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
        sqlText: 'SELECT order_id, remark FROM datanest.e2e_s13_orders WHERE remark = :remark ORDER BY order_id',
        sqlParams: [{name: 'remark', type: 'STRING', required: true}], paginated: 0,
    });
    await api.post(`/data-service/apis/${detail.id}/publish`);
    const key = (await api.post('/data-service/api-keys', {name: `${PREFIX}备注Key`, qpsLimit: 1000, apiIds: [detail.id]})).apiKey;
    await api.dispose();

    const call = (remark: string) => fetch(
        `http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}remark?remark=${encodeURIComponent(remark)}`,
        {headers: {'X-API-Key': key}},
    ).then(r => r.json());

    const normal = await call('R1');
    expect(normal.data.records).toHaveLength(1);
    expect(normal.data.records[0].order_id).toBe('1');
    const inject = await call(`R1' OR '1'='1`);
    expect(inject.data.records).toHaveLength(0);
    const stmt = await call(`R1'; DROP TABLE datanest.e2e_s13_orders --`);
    expect(stmt.data.records).toHaveLength(0);
    const {dorisStmt} = await import('../e2e/helpers/seed');
    const cnt = await dorisStmt('datanest', 'SELECT COUNT(*) c FROM e2e_s13_orders');
    expect(cnt.data.data[0][0]).toBe(6);
});

test('CS-14 字符串/注释内 :param 不误替换', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const sql = "SELECT order_id, remark FROM datanest.e2e_s13_orders WHERE remark = ':fake' OR remark = :remark -- :fake2 comment\nORDER BY order_id";
    const env = await api.raw('POST', '/data-service/apis', {
        name: `${PREFIX}字符串参数`, path: `${PATH_PREFIX}string-param`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest', sqlText: sql,
        sqlParams: [{name: 'remark', type: 'STRING', required: true}], paginated: 0,
    });
    expect(env.code).toBe(200);
    await api.dispose();
});

test('CS-15 结果截断：单次上限 1000 行（N-2）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post('/data-service/apis', {
        name: `${PREFIX}大表查询`, path: `${PATH_PREFIX}big`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
        sqlText: 'SELECT id FROM datanest.e2e_s13_big ORDER BY id', sqlParams: [], paginated: 0,
    });
    await api.post(`/data-service/apis/${detail.id}/publish`);
    const key = (await api.post('/data-service/api-keys', {name: `${PREFIX}大表Key`, qpsLimit: 1000, apiIds: [detail.id]})).apiKey;
    await api.dispose();
    const res = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}big`, {
        headers: {'X-API-Key': key},
    }).then(r => r.json());
    expect(res.data.records.length).toBe(1000);
    expect(Number(res.data.total)).toBe(1000);
});

test('CS-16 外部 orderBy 参数被忽略（D9）→ 见末尾已知缺陷用例 CS-25', async () => {
    // 用例移至末尾「已知缺陷回归」段（首行断言依赖 SQL 内 ORDER BY，当前分页包裹丢失排序）
});

// ==================== D. 编辑 / 血缘 / 审计 / 回归 ====================

test('CS-17 编辑：改 SQL 重新校验 + involvedTables 更新 + 非法 SQL 拒绝', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const ok = await api.put(`/data-service/apis/${mainApiId}`, {
        name: `${PREFIX}区域汇总V2`, path: `${PATH_PREFIX}region-sum`, queryType: 'CUSTOM_SQL',
        sqlText: 'SELECT region_id, COUNT(*) AS cnt FROM datanest.e2e_s13_orders GROUP BY region_id',
        sqlParams: [], paginated: 1, pageSizeMax: 50,
    });
    expect(ok.queryType).toBe('CUSTOM_SQL');
    expect(ok.involvedTables ?? []).toHaveLength(1);
    const bad = await api.raw('PUT', `/data-service/apis/${mainApiId}`, {
        name: `${PREFIX}区域汇总V2`, path: `${PATH_PREFIX}region-sum`, queryType: 'CUSTOM_SQL',
        sqlText: 'DELETE FROM datanest.e2e_s13_orders', sqlParams: [],
    });
    expect([9001, 9017]).toContain(bad.code);
    await api.dispose();
});

test('CS-18 血缘：创建后 lineage_record 出现涉及表（AC-7/V6）', async () => {
    const {psqlGov} = await import('../e2e/helpers/seed');
    const rows = psqlGov(`SELECT source_table, lineage_type FROM lineage_record WHERE dag_name LIKE '${PREFIX}%' ORDER BY id DESC LIMIT 10`);
    expect(rows).toContain('e2e_s13_orders');
    expect(rows).toContain('e2e_s13_region');
});

test('CS-19 审计：创建/编辑/发布/删除埋点（N-3）', async () => {
    const {psqlSys} = await import('../e2e/helpers/seed');
    // 创建/编辑审计含资源名（resourceName=#request.name）；发布/删除等 id-only 操作按项目惯例仅记 resource_id
    const rows = psqlSys(`
        SELECT op_type FROM audit_log
        WHERE resource_name LIKE '${PREFIX}%'
        ORDER BY created_at DESC LIMIT 30`);
    expect(rows).toContain('CREATE');
    expect(rows).toContain('UPDATE');
    const pub = psqlSys(`SELECT op_type FROM audit_log WHERE resource_id='${mainApiId}' AND op_type='PUBLISH'`);
    expect(pub).toContain('PUBLISH');
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post('/data-service/apis', {
        name: `${PREFIX}待删`, path: `${PATH_PREFIX}to-delete`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
        sqlText: 'SELECT order_id FROM datanest.e2e_s13_orders ORDER BY order_id', sqlParams: [], paginated: 0,
    });
    await api.del(`/data-service/apis/${detail.id}`);
    await api.dispose();
    const del = psqlSys(`SELECT op_type FROM audit_log WHERE resource_id='${detail.id}' AND op_type='DELETE'`);
    expect(del).toContain('DELETE');
});

test('CS-20 存量回归：选表形态创建/调用不变（AC-8/V7）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const detail = await api.post('/data-service/apis', {
        name: `${PREFIX}选表回归`, path: `${PATH_PREFIX}table-select`, datasourceId: BUILTIN_DORIS_ID,
        databaseName: 'datanest', tableName: 'e2e_s13_orders', metadataTableId: ORDERS_META_ID,
        filters: [{field: 'region_id', type: 'EQ'}], fields: ['order_id', 'region_id', 'amount'],
        paginated: 1, pageSizeMax: 50,
    });
    expect(detail.queryType).toBe('TABLE_SELECT');
    await api.post(`/data-service/apis/${detail.id}/publish`);
    const key = (await api.post('/data-service/api-keys', {name: `${PREFIX}选表Key`, qpsLimit: 1000, apiIds: [detail.id]})).apiKey;
    await api.dispose();
    const res = await fetch(`http://localhost:8080/api/data-service/open-api/v1/${PATH_PREFIX}table-select?region_id=1&pageSize=10`, {
        headers: {'X-API-Key': key},
    }).then(r => r.json());
    expect(Number(res.data.total)).toBe(2);
    expect(res.data.records[0].order_id).toBe('1');
    expect(res.data.records[0]).not.toHaveProperty('remark');
});

test('CS-21 权限矩阵：分析师写操作 403（对齐一期）', async () => {
    const api = await Api.create();
    await api.login(`${PREFIX}analyst`, PW);
    const env = await api.raw('POST', '/data-service/apis', {
        name: `${PREFIX}越权`, path: `${PATH_PREFIX}forbidden`, queryType: 'CUSTOM_SQL',
        datasourceId: BUILTIN_DORIS_ID, databaseName: 'datanest',
        sqlText: 'SELECT order_id FROM datanest.e2e_s13_orders', sqlParams: [],
    });
    expect([1005, 403]).toContain(env.code);
    await api.dispose();
});

test('CS-22 列表形态筛选：queryType=CUSTOM_SQL 只回自定义 SQL（t7）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const page = await api.get('/data-service/apis/page?page=1&pageSize=100&queryType=CUSTOM_SQL');
    expect(page.total > 0).toBeTruthy();
    for (const item of page.records) {
        expect(item.queryType).toBe('CUSTOM_SQL');
    }
    await api.dispose();
});
