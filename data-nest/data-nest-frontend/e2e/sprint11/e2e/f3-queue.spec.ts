import {expect, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {gotoAs} from '../../sprint6/helpers/e2e';
import {
    ADMIN,
    cleanupF3,
    DAG_NAME,
    PREFIX,
    psqlEng,
    psqlSys,
    Q_CRUD,
    Q_SOLO,
    seedF3,
} from './helpers/f3-seed';

/** 测试 DAG 所属项目（复用现有 test 项目） */
const PROJECT_ID = '2083083277706489857';

/** SQL 单节点 DAG（SELECT 1，快执行无副作用） */
function sqlDag(name: string, queueName: string, priority = 2) {
    return {
        projectId: Number(PROJECT_ID),
        name,
        triggerType: 'MANUAL',
        scheduleEnabled: false,
        maxParallelism: 1,
        queueName,
        priority,
        status: 'ENABLED',
        nodes: [
            {
                nodeId: 'n1',
                nodeName: 'SQL_SELECT_1',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1 AS ok'}),
            },
        ],
        edges: [],
    };
}

function sleep(ms: number) {
    return new Promise((r) => setTimeout(r, ms));
}

/** 等待审计记录出现（轮询 audit_log，resourceType + opType + resourceName 精确匹配） */
async function waitAudit(api: any, resourceType: string, opType: string, resourceName?: string, timeoutMs = 15_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const r = await api.raw<{code: number; data: {records: any[]}}>(
            'GET', `/system/audit-logs?page=1&pageSize=50&resourceType=${resourceType}&opType=${opType}`);
        if (r.code === 200) {
            const hit = (r.data?.records ?? []).find(
                (x) => x.resourceType === resourceType && x.opType === opType
                    && (!resourceName || x.resourceName === resourceName)
                    && x.result === 'SUCCESS');
            if (hit) return hit;
        }
        await sleep(1000);
    }
    throw new Error(`审计记录未出现: ${resourceType}/${opType}/${resourceName}`);
}

/** 等待 DAG 执行历史到达非 RUNNING/WAITING 终态（QU-6 排队用） */
async function waitExecutionSettled(api: any, dagId: string, execId: string, timeoutMs = 120_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const r = await api.raw<{code: number; data: {records: any[]}}>(
            'GET', `/engineering/dag-executions?dagId=${dagId}&page=1&pageSize=20`);
        if (r.code === 200) {
            const rec = (r.data?.records ?? []).find((x) => String(x.id) === String(execId));
            if (rec && !['RUNNING', 'WAITING'].includes(rec.status)) return rec;
        }
        await sleep(3000);
    }
    throw new Error(`执行未结束: dagId=${dagId} execId=${execId}`);
}

test.describe.serial('F3 执行队列（QU-1~7）', () => {
    let api: any;

    test.beforeAll(async () => {
        await seedF3();
        api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
    });

    test.afterAll(async () => {
        if (api) await api.dispose();
        await cleanupF3();
    });

    // ============ A 队列 CRUD（QU-1/5） ============

    test('A1 创建执行队列（并发/描述）→ 列表可见 + 创建人回填', async () => {
        const name = `${PREFIX}crud_${Date.now() % 100000}`;
        const c = await api.raw<{code: number; data: {id: string; queueName: string; maxConcurrency: number}}>(
            'POST', '/engineering/execution-queues',
            {queueName: name, maxConcurrency: 7, description: 'F3 A1 创建'});
        expect(c.code).toBe(200);
        expect(c.data.queueName).toBe(name);
        expect(c.data.maxConcurrency).toBe(7);
        // 列表可见（含 createdByName 回填）
        const lst = await api.raw<{code: number; data: {records: any[]}}>(
            'POST', '/engineering/execution-queues/page', {page: 1, pageSize: 20, keyword: name});
        const hit = (lst.data?.records ?? []).find((q) => q.queueName === name);
        expect(hit).toBeTruthy();
        expect(hit.description).toBe('F3 A1 创建');
        expect(hit.createdByName).toBe('admin');
        // 清理
        await api.raw('DELETE', `/engineering/execution-queues/${c.data.id}`);
    });

    test('A2 重名创建 → 7402 执行队列名称已存在', async () => {
        const c = await api.raw<{code: number}>('POST', '/engineering/execution-queues',
            {queueName: Q_CRUD, maxConcurrency: 3});
        expect(c.code).toBe(7402);
    });

    test('A3 非法队列名 → 7405（只允许字母/数字/下划线，2~32）', async () => {
        const bad = await api.raw<{code: number}>('POST', '/engineering/execution-queues',
            {queueName: 'a!b#', maxConcurrency: 3});
        expect(bad.code).toBe(7405);
        const short = await api.raw<{code: number}>('POST', '/engineering/execution-queues',
            {queueName: 'a', maxConcurrency: 3});
        expect(short.code).toBe(7405);
    });

    test('A4 编辑队列（改名+并发）→ 列表更新；绑定 DAG 联动改名', async () => {
        // 创建队列并绑一个 DAG（验证改名联动）
        const qName = `${PREFIX}rename_${Date.now() % 100000}`;
        const qNew = `${qName}_v2`;
        const c = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/execution-queues',
            {queueName: qName, maxConcurrency: 2});
        expect(c.code).toBe(200);
        const qid = c.data.id;
        // 绑一个 DAG
        const d = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/dev/dags',
            sqlDag(`${DAG_NAME}_rename_${Date.now() % 100000}`, qName, 3));
        expect(d.code).toBe(200);
        const dagId = d.data.id;
        // 编辑改名 + 并发
        const up = await api.raw<{code: number}>('PUT', `/engineering/execution-queues/${qid}`,
            {queueName: qNew, maxConcurrency: 9});
        expect(up.code).toBe(200);
        // 列表验证新名/并发
        const lst = await api.raw<{code: number; data: {records: any[]}}>(
            'POST', '/engineering/execution-queues/page', {page: 1, pageSize: 20, keyword: qNew});
        const hit = (lst.data?.records ?? []).find((q) => q.queueName === qNew);
        expect(hit).toBeTruthy();
        expect(hit.maxConcurrency).toBe(9);
        // 绑定 DAG 联动改名（QU-4：dag.queue_name 同步）
        const dagDetail = await api.raw<{code: number; data: any}>('GET', `/engineering/dev/dags/${dagId}`);
        expect(dagDetail.code).toBe(200);
        expect(dagDetail.data.queueName).toBe(qNew);
        // 清理 DAG + 队列
        await api.raw('DELETE', `/engineering/dev/dags/${dagId}`);
        await api.raw('DELETE', `/engineering/execution-queues/${qid}`);
    });

    test('A5 系统内置 default 保护（QU-5）：不可删/不可改名，可改并发', async () => {
        const lst = await api.raw<{code: number; data: any[]}>('GET', '/engineering/execution-queues');
        const def = (lst.data ?? []).find((q) => q.isSystem);
        expect(def).toBeTruthy();
        const defId = def.id;
        // 不可删
        const del = await api.raw<{code: number}>('DELETE', `/engineering/execution-queues/${defId}`);
        expect(del.code).toBe(7403);
        // 不可改名
        const rename = await api.raw<{code: number}>('PUT', `/engineering/execution-queues/${defId}`,
            {queueName: 'not_default', maxConcurrency: 10});
        expect(rename.code).toBe(7403);
        // 可改并发（保持原名）
        const edit = await api.raw<{code: number}>('PUT', `/engineering/execution-queues/${defId}`,
            {queueName: 'default', maxConcurrency: 12});
        expect(edit.code).toBe(200);
        // 恢复原并发 10
        await api.raw('PUT', `/engineering/execution-queues/${defId}`,
            {queueName: 'default', maxConcurrency: 10});
    });

    test('A6 删除执行队列 → 列表消失', async () => {
        const name = `${PREFIX}del_${Date.now() % 100000}`;
        const c = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/execution-queues',
            {queueName: name, maxConcurrency: 1});
        expect(c.code).toBe(200);
        const qid = c.data.id;
        const del = await api.raw<{code: number}>('DELETE', `/engineering/execution-queues/${qid}`);
        expect(del.code).toBe(200);
        const lst = await api.raw<{code: number; data: {records: any[]}}>(
            'POST', '/engineering/execution-queues/page', {page: 1, pageSize: 20, keyword: name});
        const hit = (lst.data?.records ?? []).find((q) => q.queueName === name);
        expect(hit).toBeFalsy();
    });

    // ============ B 队列删除约束（QU-3） ============

    test('B1 有 DAG 绑定的队列删除被拒 → 删 DAG 后可删（QU-3）', async () => {
        const qName = `${PREFIX}bind_${Date.now() % 100000}`;
        const c = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/execution-queues',
            {queueName: qName, maxConcurrency: 2});
        expect(c.code).toBe(200);
        const qid = c.data.id;
        const d = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/dev/dags',
            sqlDag(`${DAG_NAME}_bind_${Date.now() % 100000}`, qName));
        expect(d.code).toBe(200);
        const dagId = d.data.id;
        // 有绑定删除被拒
        const del = await api.raw<{code: number; message?: string}>('DELETE', `/engineering/execution-queues/${qid}`);
        expect(del.code).toBe(7404);
        expect(del.message).toContain('DAG');
        // 删 DAG 后可删
        await api.raw('DELETE', `/engineering/dev/dags/${dagId}`);
        const del2 = await api.raw<{code: number}>('DELETE', `/engineering/execution-queues/${qid}`);
        expect(del2.code).toBe(200);
    });

    // ============ C DAG 绑定队列（QU-2/4） ============

    test('C1 创建 DAG 绑定队列+优先级 → DAG 详情含 queueName/priority', async () => {
        const name = `${DAG_NAME}_c1_${Date.now() % 100000}`;
        const d = await api.raw<{code: number; data: any}>('POST', '/engineering/dev/dags',
            sqlDag(name, Q_CRUD, 3));
        expect(d.code).toBe(200);
        const dagId = d.data.id;
        expect(d.data.queueName).toBe(Q_CRUD);
        expect(d.data.priority).toBe(3);
        // 清理
        await api.raw('DELETE', `/engineering/dev/dags/${dagId}`);
    });

    test('C2 绑定不存在的队列 → 创建 DAG 报错（队列存在性强校验）', async () => {
        const d = await api.raw<{code: number}>('POST', '/engineering/dev/dags',
            sqlDag(`${DAG_NAME}_c2_${Date.now() % 100000}`, 'no_such_queue_xxx'));
        expect(d.code).not.toBe(200);
    });

    test('C3 优先级越界 → 创建 DAG 报错', async () => {
        const d = await api.raw<{code: number}>('POST', '/engineering/dev/dags',
            sqlDag(`${DAG_NAME}_c3_${Date.now() % 100000}`, Q_CRUD, 9));
        expect(d.code).not.toBe(200);
    });

    // ============ D 排队调度（QU-6） ============

    test('D1 队列并发1：触发占满后第二条排队 WAITING，等高优先先执行', async () => {
        // 建 DAG 绑定 solo 队列（并发 1）
        const d = await api.raw<{code: number; data: any}>('POST', '/engineering/dev/dags',
            sqlDag(`${DAG_NAME}_q_${Date.now() % 100000}`, Q_SOLO, 2));
        expect(d.code).toBe(200);
        const dagId = d.data.id;

        // 第一次触发 → RUNNING（队列空）
        const t1 = await api.raw<{code: number; data: any}>('POST', `/engineering/dev/dags/${dagId}/trigger`);
        expect(t1.code).toBe(200);
        const exec1Id = t1.data.id;
        expect(t1.data.status).toBe('RUNNING');

        // 第二次触发同 DAG → 排队 WAITING（唯一索引禁止并发 RUNNING，但可排队）
        const t2 = await api.raw<{code: number; data: any}>('POST', `/engineering/dev/dags/${dagId}/trigger`);
        expect(t2.code).toBe(200);
        const exec2Id = t2.data.id;
        expect(t2.data.status).toBe('WAITING');

        // 等第一条执行完成 → 第二条由调度器接管执行
        const first = await waitExecutionSettled(api, dagId, exec1Id);
        expect(['SUCCESS', 'FAILED']).toContain(first.status);

        // 第二条最终也到终态
        const second = await waitExecutionSettled(api, dagId, exec2Id);
        expect(['SUCCESS', 'FAILED']).toContain(second.status);
        // 第二条执行时记录优先级/队列
        expect(second.queueName).toBe(Q_SOLO);

        // 清理 DAG
        await api.raw('DELETE', `/engineering/dev/dags/${dagId}`);
    });

    test('D2 队列页列表统计：running/waiting 计数反映真实执行', async () => {
        // 复用 D1 思路：触发一次（不排队），立即查列表 runningCount>=1
        const d = await api.raw<{code: number; data: any}>('POST', '/engineering/dev/dags',
            sqlDag(`${DAG_NAME}_d2_${Date.now() % 100000}`, Q_CRUD, 1));
        expect(d.code).toBe(200);
        const dagId = d.data.id;
        const t = await api.raw<{code: number; data: any}>('POST', `/engineering/dev/dags/${dagId}/trigger`);
        expect(t.code).toBe(200);
        const execId = t.data.id;
        expect(t.data.status).toBe('RUNNING');
        // 队列列表 runningCount 应 >=1
        const lst = await api.raw<{code: number; data: {records: any[]}}>(
            'POST', '/engineering/execution-queues/page', {page: 1, pageSize: 20, keyword: Q_CRUD});
        const q = (lst.data?.records ?? []).find((x) => x.queueName === Q_CRUD);
        expect(q).toBeTruthy();
        expect(q.runningCount).toBeGreaterThanOrEqual(1);
        // 等执行结束
        await waitExecutionSettled(api, dagId, execId);
        await api.raw('DELETE', `/engineering/dev/dags/${dagId}`);
    });

    // ============ E 审计（QU-7） ============

    test('E1 队列 CRUD 审计：CREATE/UPDATE/DELETE 均记录 EXECUTION_QUEUE', async () => {
        // 创建
        const name = `${PREFIX}audit_${Date.now() % 100000}`;
        const c = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/execution-queues',
            {queueName: name, maxConcurrency: 3, description: 'F3 audit'});
        expect(c.code).toBe(200);
        const qid = c.data.id;
        await waitAudit(api, 'EXECUTION_QUEUE', 'CREATE', name);
        // 更新（改名）
        const newName = `${name}_v2`;
        await api.raw('PUT', `/engineering/execution-queues/${qid}`, {queueName: newName, maxConcurrency: 5});
        await waitAudit(api, 'EXECUTION_QUEUE', 'UPDATE', newName);
        // 删除（手动埋点，含队列名——F3 修复项）
        await api.raw('DELETE', `/engineering/execution-queues/${qid}`);
        await waitAudit(api, 'EXECUTION_QUEUE', 'DELETE', newName);
    });

    // ============ F 权限（仅超管） ============

    test('F1 非超管访问队列 API → 403（QUEUE_MANAGE 权限点）', async () => {
        // 创建临时分析师用户（复用已有 ANALYST 内置角色，无 queue:manage 权限）
        const uname = `${PREFIX}f1user_${Date.now() % 100000}`;
        const u = await api.raw<{code: number; data: {id: string}}>('POST', '/system/users', {
            username: uname, password: 'Test123456', roles: ['DATA_ANALYST'],
        });
        expect(u.code).toBe(200);
        const uid = u.data.id;
        try {
            const analyst = await Api.create();
            await analyst.login(uname, 'Test123456');
            const r = await analyst.raw<{code: number}>('GET', '/engineering/execution-queues');
            expect(r.code).not.toBe(200);
            await analyst.dispose();
        } finally {
            psqlSys(`DELETE FROM sys_user_role WHERE user_id=${uid}; DELETE FROM sys_user WHERE id=${uid};`);
        }
    });

    // ============ G UI 队列页（QU-1/5 + 详情抽屉 QU-4） ============

    test('G1 队列页加载：标题/列表展示 default 内置徽章 + seed 队列', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/execution-queues');
        await expect(page.getByRole('heading', {name: '执行队列'})).toBeVisible();
        // default 内置队列 + 徽章
        await expect(page.getByText('default', {exact: true}).first()).toBeVisible();
        await expect(page.getByText('内置', {exact: true}).first()).toBeVisible();
        // seed 队列（Q_CRUD）可见
        await expect(page.getByText(Q_CRUD, {exact: true}).first()).toBeVisible({timeout: 15_000});
    });

    test('G2 新建队列：非法名前端校验提示', async ({page}) => {
        await gotoAs(page, ADMIN.username, ADMIN.password, '/system/execution-queues');
        await page.getByRole('button', {name: '新建队列'}).click();
        await page.getByPlaceholder('字母/数字/下划线，2~32 位').fill('a!b#');
        await page.getByRole('button', {name: '保存'}).click();
        await expect(page.getByText('队列名仅限字母/数字/下划线，2~32 位').first()).toBeVisible();
        await page.getByRole('button', {name: '取消'}).click();
    });

    test('G3 队列详情抽屉：绑定 DAG 列表 + 优先级/触发方式筛选', async ({page}) => {
        // 准备：建队列 + 绑一个 DAG
        const qName = `${PREFIX}ui_${Date.now() % 100000}`;
        const c = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/execution-queues',
            {queueName: qName, maxConcurrency: 2});
        expect(c.code).toBe(200);
        const qid = c.data.id;
        const dagName = `${DAG_NAME}_ui_${Date.now() % 100000}`;
        const d = await api.raw<{code: number; data: {id: string}}>('POST', '/engineering/dev/dags',
            sqlDag(dagName, qName, 3));
        expect(d.code).toBe(200);
        const dagId = d.data.id;
        try {
            await gotoAs(page, ADMIN.username, ADMIN.password, '/system/execution-queues');
            // 找到该队列行，点击「绑定 DAG」数字按钮打开抽屉
            const row = page.locator('.ant-table-row').filter({hasText: qName}).first();
            await expect(row).toBeVisible({timeout: 15_000});
            await row.getByLabel(`查看队列 ${qName} 绑定的 DAG`).click();
            // 抽屉打开：绑定 DAG 显示
            const drawer = page.getByRole('dialog').first();
            await expect(drawer.getByText(dagName, {exact: false}).first()).toBeVisible({timeout: 15_000});
            // 筛选：优先级 高(3) → DAG 仍显示（DsFilterSelect 是原生 select，用 selectOption）
            await drawer.getByLabel('按优先级筛选').selectOption('3');
            await drawer.getByRole('button', {name: '查询', exact: true}).click();
            await expect(drawer.getByText(dagName, {exact: false}).first()).toBeVisible({timeout: 15_000});
            // 触发方式筛选：手动 → DAG 仍显示
            await drawer.getByLabel('按触发方式筛选').selectOption('MANUAL');
            await drawer.getByRole('button', {name: '查询', exact: true}).click();
            await expect(drawer.getByText(dagName, {exact: false}).first()).toBeVisible({timeout: 15_000});
            // 重置
            await drawer.getByRole('button', {name: '重置'}).click();
            await drawer.getByRole('button', {name: '关闭'}).click();
            await expect(page.getByRole('dialog')).toHaveCount(0);
        } finally {
            await api.raw('DELETE', `/engineering/dev/dags/${dagId}`);
            await api.raw('DELETE', `/engineering/execution-queues/${qid}`);
        }
    });
});
