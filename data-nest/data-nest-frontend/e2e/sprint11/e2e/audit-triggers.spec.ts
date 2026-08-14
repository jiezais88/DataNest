import {expect, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    ANALYST,
    PREFIX,
    PATH_PREFIX,
    TARGET,
    cleanupS11,
    ensureUser,
    findAudit,
    getTableId,
    resetSensitivity,
} from './helpers/seed';

/**
 * Sprint 11 F1 审计触发链路 E2E（埋点覆盖验证，PRD §6.1.1 十类操作中的已实现 8 类）。
 *
 * 范围说明（用户确认 2026-08-14）：
 * - 第 2 类「权限变更」（角色 CRUD / 数据权限保存）与第 10 类「执行队列」属 F2/F3 功能，
 *   当前未实现 → 记为预期缺口，F2/F3 交付后再补测。
 * - 第 1/3/4/5/6/7/8/9 类已实现，逐类验证：触发真实业务操作 → 审计日志出现正确记录。
 *
 * 造数方式（用户确认）：API 造数触发为主（埋点在 Controller 层，API 调用等价触发），
 * SQL 查询成功/失败、改级等核心链路按需走真实调用；审计页 UI 全功能在 audit-page.spec.ts。
 *
 * 断言要点（对齐 PRD §6.1.2 审计记录内容）：
 * - operatorName = 操作人（admin）；opType/resourceType 精确；resourceName 可读；
 * - content 按资源类型含摘要（SQL 含行数/耗时）；result = SUCCESS/FAILURE；
 * - SQL 机密拦截（AL-3）：result=FAILURE 且 errorMessage 含「机密」。
 *
 * 环境约定：审计写入异步 fail-open，findAudit 轮询；敏感度闸门用例改级 target_products 测后复位。
 */

test.describe.configure({mode: 'serial'});

const ts = () => Date.now();
const n = (prefix: string) => `${PREFIX}${prefix}_${ts()}`;

// ==================== 播种 / 清理 ====================

test.beforeAll(async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    // 权限隔离用例账号（幂等创建）
    await ensureUser(api, ANALYST);
    // 复位主测试表敏感度
    resetSensitivity();
    await api.dispose();
});

test.afterAll(async () => {
    await cleanupS11();
});

// ==================== 第 1 类：用户管理 ====================

test('T1 创建用户 → 审计 CREATE/USER（操作人 admin / 资源名 / 结果成功）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const username = n('u');
    await api.post('/system/users', {username, password: 'Test123456', roles: ['DATA_ANALYST'], email: `${username}@test.io`});
    try {
        const rec = await findAudit(api, {opType: 'CREATE', resourceType: 'USER', keyword: username, result: 'SUCCESS'});
        expect(rec.operatorName).toBe('admin');
        expect(rec.resourceName).toContain(username);
        expect(rec.clientIp).toBeTruthy();
    } finally {
        await api.dispose();
    }
});

test('T2 禁用/启用用户 → 审计 UPDATE/USER（PRD 第 1 类「禁用用户、启用用户」）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    // 先创建目标用户（创建本身也记 CREATE，但这里断言 toggle 动作）
    const username = n('toggle');
    const user = await api.post<{id: string}>('/system/users', {username, password: 'Test123456', roles: ['DATA_ANALYST'], email: `${username}@test.io`});
    // toggle 埋点 opType=UPDATE（启用/禁用共用，语义观察项 OBS-S11-01）
    await api.put(`/system/users/${user.id}/toggle`);
    try {
        const rec = await findAudit(api, {opType: 'UPDATE', resourceType: 'USER', keyword: username});
        expect(rec.operatorName).toBe('admin');
        expect(rec.resourceName).toContain(username);
    } finally {
        await api.dispose();
    }
});

test('T3 重置密码 → 审计 RESET_PASSWORD/USER', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const username = n('pwd');
    const user = await api.post<{id: string}>('/system/users', {username, password: 'Test123456', roles: ['DATA_ANALYST'], email: `${username}@test.io`});
    await api.put(`/system/users/${user.id}/reset-password`, {newPassword: 'NewPass123'});
    try {
        const rec = await findAudit(api, {opType: 'RESET_PASSWORD', resourceType: 'USER', keyword: username, result: 'SUCCESS'});
        expect(rec.operatorName).toBe('admin');
        expect(rec.resourceName).toContain(username);
        // 安全约束 B3：content/errorMessage 不含明文密码
        expect(rec.content ?? '').not.toContain('NewPass123');
    } finally {
        await api.dispose();
    }
});

// ==================== 第 3 类：数据源管理 ====================

test('T4 创建数据源 + 测试连接 → 审计 CREATE/TEST 双记录', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const dsName = n('ds');
    const ds = await api.post<{id: string}>('/engineering/datasources', {
        name: dsName, type: 'MYSQL', host: '127.0.0.1', port: 3306,
        databaseName: 'testdb', username: 'root', password: 'secret', description: 'sprint11 audit e2e',
    });
    // 测试连接：用新建的数据源（连不上是预期，失败也记审计）
    await api.post(`/engineering/datasources/${ds.id}/test`);
    try {
        const createRec = await findAudit(api, {opType: 'CREATE', resourceType: 'DATASOURCE', keyword: dsName});
        expect(createRec.operatorName).toBe('admin');
        expect(createRec.resourceName).toContain(dsName);
        const testRec = await findAudit(api, {opType: 'TEST', resourceType: 'DATASOURCE', keyword: dsName});
        expect(testRec.resourceName).toContain(dsName);
    } finally {
        await api.dispose();
    }
});

// ==================== 第 4 类：批量同步任务 ====================

test('T5 创建同步任务 → 审计 CREATE/SYNC_JOB', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const jobName = n('sync');
    await api.post('/engineering/sync-jobs', {
        name: jobName,
        sourceDatasourceId: Number(TARGET.datasourceId),
        sourceDatabase: TARGET.databaseName,
        sourceTables: [TARGET.tableName],
        syncMode: 'FULL',
        triggerType: 'MANUAL',
        targetDatabase: TARGET.databaseName,
        targetTable: n('sync_tgt'),
        retryTimes: 0, retryInterval: 5, rateLimitEnabled: false,
    });
    try {
        const rec = await findAudit(api, {opType: 'CREATE', resourceType: 'SYNC_JOB', keyword: jobName, result: 'SUCCESS'});
        expect(rec.operatorName).toBe('admin');
        expect(rec.resourceName).toContain(jobName);
    } finally {
        await api.dispose();
    }
});

// ==================== 第 5 类：DAG 编排 ====================

test('T6 创建 DAG + 手动触发 → 审计 CREATE/TRIGGER 双记录', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const dagName = n('dag');
    const proj = await api.post<{id: string}>('/engineering/dev/dag-projects', {name: n('proj'), description: 'sprint11 audit e2e'});
    const dag = await api.post<{id: string}>('/engineering/dev/dags', {
        projectId: proj.id, name: dagName, triggerType: 'MANUAL', scheduleEnabled: false,
        maxParallelism: 1, status: 'ENABLED',
        nodes: [{nodeId: 'n1', nodeName: 'SQL1', nodeType: 'SQL', positionX: 0, positionY: 0, config: '{"type":"SQL","sqlContent":"select 1"}'}],
        edges: [],
    });
    // 手动触发（SQL 节点 select 1 安全；触发结果成功/失败均记审计，只断言动作留痕）
    await api.post(`/engineering/dev/dags/${dag.id}/trigger`);
    try {
        const createRec = await findAudit(api, {opType: 'CREATE', resourceType: 'DAG', keyword: dagName});
        expect(createRec.operatorName).toBe('admin');
        expect(createRec.resourceName).toContain(dagName);
        const triggerRec = await findAudit(api, {opType: 'TRIGGER', resourceType: 'DAG', keyword: dagName});
        expect(triggerRec.operatorName).toBe('admin');
        expect(triggerRec.resourceName).toContain(dagName);
    } finally {
        await api.dispose();
    }
});

// ==================== 第 6 类：SQL 查询 ====================

test('T7 SQL 查询成功 → 审计 EXECUTE/SQL_QUERY（content 含行数/耗时）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    // 前置：target_products 必须为 PUBLIC（T10 改级用例前执行，顺序由串行保证）
    const sql = `SELECT * FROM ${TARGET.databaseName}.${TARGET.tableName} LIMIT 3`;
    await api.post<{rowCount?: number}>('/data-service/sql-console/execute', {datasourceId: Number(TARGET.datasourceId), sql});
    try {
        const rec = await findAudit(api, {opType: 'EXECUTE', resourceType: 'SQL_QUERY', keyword: 'target_products', result: 'SUCCESS'});
        expect(rec.operatorName).toBe('admin');
        // AL-2：SQL 摘要 + 行数 + 耗时
        expect(rec.content).toContain('SELECT * FROM datanest.target_products');
        expect(rec.content).toContain('行数:');
        expect(rec.content).toContain('耗时:');
        // 资源名 = 数据源名（内置 Doris）
        expect(rec.resourceName).toBeTruthy();
    } finally {
        await api.dispose();
    }
});

// ==================== 第 7 类：数据 API 管理 ====================

test('T8 创建 API + 发布 → 审计 CREATE/PUBLISH 双记录', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const apiName = n('api');
    const apiPath = `${PATH_PREFIX}api-${ts()}`;
    const created = await api.post<{id: string}>('/data-service/apis', {
        name: apiName, path: apiPath,
        datasourceId: Number(TARGET.datasourceId),
        databaseName: TARGET.databaseName,
        tableName: TARGET.tableName,
        metadataTableId: Number(getTableId()),
        paginated: 1, pageSizeMax: 100,
    });
    await api.post(`/data-service/apis/${created.id}/publish`);
    try {
        const createRec = await findAudit(api, {opType: 'CREATE', resourceType: 'DATA_API', keyword: apiName, result: 'SUCCESS'});
        expect(createRec.operatorName).toBe('admin');
        expect(createRec.resourceName).toContain(apiName);
        const publishRec = await findAudit(api, {opType: 'PUBLISH', resourceType: 'DATA_API', keyword: apiName, result: 'SUCCESS'});
        expect(publishRec.operatorName).toBe('admin');
    } finally {
        await api.dispose();
    }
});

// ==================== 第 8 类：API Key 管理 ====================

test('T9 创建 Key + 禁用 → 审计 CREATE/DISABLE 双记录（不记明文）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const keyName = n('key');
    const key = await api.post<{id: string; key?: string}>('/data-service/api-keys', {name: keyName, qpsLimit: 100, apiIds: []});
    await api.post(`/data-service/api-keys/${key.id}/disable`);
    try {
        const createRec = await findAudit(api, {opType: 'CREATE', resourceType: 'API_KEY', keyword: keyName, result: 'SUCCESS'});
        expect(createRec.operatorName).toBe('admin');
        expect(createRec.resourceName).toContain(keyName);
        // 安全约束 B3：不记录 Key 明文
        if (key.key) {
            expect(createRec.content ?? '').not.toContain(key.key);
        }
        const disableRec = await findAudit(api, {opType: 'DISABLE', resourceType: 'API_KEY', keyword: keyName, result: 'SUCCESS'});
        expect(disableRec.operatorName).toBe('admin');
    } finally {
        await api.dispose();
    }
});

// ==================== 第 9 类：数据分级分类（改级 → 机密） ====================

test('T10 改级 target_products → CONFIDENTIAL → 审计 CHANGE_LEVEL/SENSITIVITY', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const tableId = getTableId();
    // 改级走真实接口（超管权限），审计由 SensitivityService 程序化记录
    await api.raw('PUT', `/governance/metadata/tables/${tableId}/sensitivity`, {newLevel: 'CONFIDENTIAL'});
    try {
        const rec = await findAudit(api, {opType: 'CHANGE_LEVEL', resourceType: 'SENSITIVITY', keyword: TARGET.tableName, result: 'SUCCESS'});
        expect(rec.operatorName).toBe('admin');
        // AL-4：资源 = 库名.表名，内容 = 旧等级→新等级
        expect(rec.resourceName).toContain(`${TARGET.databaseName}.${TARGET.tableName}`);
        expect(rec.content).toContain('PUBLIC');
        expect(rec.content).toContain('CONFIDENTIAL');
    } finally {
        await api.dispose();
    }
});

test('T11 SQL 查询机密表被拦截 → 审计 EXECUTE/SQL_QUERY 失败（errorMessage 含机密，AL-3）', async () => {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    // 前置：T10 已把 target_products 改为 CONFIDENTIAL（串行保证）
    const sql = `SELECT * FROM ${TARGET.databaseName}.${TARGET.tableName} LIMIT 3`;
    const env = await api.raw('POST', '/data-service/sql-console/execute', {datasourceId: Number(TARGET.datasourceId), sql});
    // 机密拦截应非 200（业务拒绝）
    expect(env.code).not.toBe(200);
    try {
        const rec = await findAudit(api, {opType: 'EXECUTE', resourceType: 'SQL_QUERY', keyword: 'target_products', result: 'FAILURE'});
        // AL-3：失败原因包含「机密」
        expect(rec.errorMessage ?? '').toContain('机密');
        expect(rec.operatorName).toBe('admin');
    } finally {
        await api.dispose();
    }
});

// ==================== 权限隔离（AL-9 的 API 侧断言，UI 侧在 audit-page.spec.ts） ====================

test('T12 非超管调审计接口 → 403（AL-9 辅助诊断）', async () => {
    const api = await Api.create();
    await api.login(ANALYST.username, ANALYST.password);
    try {
        const env = await api.raw('GET', '/system/audit-logs?page=1&pageSize=10');
        expect(env.code).toBe(403);
    } finally {
        await api.dispose();
    }
});
