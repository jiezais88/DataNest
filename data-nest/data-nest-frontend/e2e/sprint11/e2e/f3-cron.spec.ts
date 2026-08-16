import {expect, test} from '@playwright/test';
import {Api} from '../../sprint6/helpers/api';
import {
    ADMIN,
    cleanupF3,
    psqlEng,
    seedF3,
} from './helpers/f3-seed';

/**
 * Sprint 11 F3 方案 A：cron 定时任务纳入执行队列控制（2026-08-16）
 *
 * 机制（改造后）：DAG 定时触发不再走 workflow 内嵌 cron，改为 job 侧独立 cron job
 * （DagScheduledTriggerHandler，jobParams=dagId），到点经 Feign 调 engineering
 * /internal/dag/scheduled-trigger → 与手动触发共用排队链路（队列满→WAITING 入池，
 * 空→RUNNING 直接执行），执行历史 trigger_type=SCHEDULED 可区分来源。
 *
 * 测试策略：API 为主（验证 cron job 注册/注销 + SCHEDULED 排队/直跑）+ UI 执行历史
 * SCHEDULED 显示（TRIGGER_LABEL 已补）。触发方式：PowerJob OpenAPI runJob 模拟 cron 到点。
 */

const PREFIX = 'e2e_s11f3c_';
const PJ_BASE = 'http://localhost:7700';
const PJ_APP_ID = 1; // data-nest-job
const POWERJOB_ADDR = 'http://localhost:7700';

// ==================== 工具 ====================

async function loginAdmin() {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    return api;
}

/** 创建 CRON DAG（纯 SQL 节点快执行），返回 {id, name, schedulerJobId} */
async function createCronDag(api: any, name: string, cron = '0 0 3 * * ?', scheduleEnabled = true, queue = 'default') {
    const d = await api.raw<{code: number; data: any}>('POST', '/engineering/dev/dags', {
        projectId: 2083083277706489857,
        name,
        triggerType: 'CRON',
        cronExpression: cron,
        scheduleEnabled,
        maxParallelism: 1,
        queueName: queue,
        priority: 2,
        status: 'ENABLED',
        nodes: [{
            nodeId: 'n1', nodeName: 'SQL_SELECT_1', nodeType: 'SQL',
            positionX: 0, positionY: 0,
            config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1 AS ok'}),
        }],
        edges: [],
    });
    expect(d.code, `创建 CRON DAG 失败: ${JSON.stringify(d)}`).toBe(200);
    return {id: String(d.data.id), name: d.data.name, ...d.data};
}

/** 创建带 PYTHON sleep 慢节点的 CRON DAG（用于占满队列；sleepSeconds 控制执行时长） */
async function createCronDagSleep(api: any, name: string, queue: string, sleepSeconds = 20) {
    const d = await api.raw<{code: number; data: any}>('POST', '/engineering/dev/dags', {
        projectId: 2083083277706489857,
        name,
        triggerType: 'CRON',
        cronExpression: '0 0 4 * * ?',
        scheduleEnabled: true,
        maxParallelism: 1,
        queueName: queue,
        priority: 2,
        status: 'ENABLED',
        nodes: [{
            nodeId: 'n1', nodeName: 'PY_SLEEP_1', nodeType: 'PYTHON',
            positionX: 0, positionY: 0,
            config: JSON.stringify({
                type: 'PYTHON',
                pythonScript: `import time\ntime.sleep(${sleepSeconds})`,
                timeoutMinutes: 30,
                memoryLimitMb: 512,
            }),
        }],
        edges: [],
    });
    expect(d.code, `创建 CRON 慢 DAG 失败: ${JSON.stringify(d)}`).toBe(200);
    return {id: String(d.data.id), name: d.data.name, ...d.data};
}

/** 查 DAG 的 scheduler_job_id */
function dagSchedulerJobId(dagId: string): string {
    return psqlEng(`SELECT scheduler_job_id FROM dag WHERE id=${dagId};`).trim();
}

/** 触发 PowerJob cron job（OpenAPI runJob，等价 cron 到点；jobParams=dagId 由 job handler 使用） */
async function triggerPowerJobJob(jobId: string): Promise<number> {
    const {execSync} = await import('child_process');
    const out = execSync(
        `python -c "import urllib.request; r=urllib.request.urlopen(urllib.request.Request('http://localhost:7700/openApi/runJob?appId=${PJ_APP_ID}&jobId=${jobId}', method='POST')); print(r.read().decode())"`,
        {encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024});
    const r = JSON.parse(out);
    expect(r.success, `PowerJob runJob 失败: ${out}`).toBe(true);
    return r?.data ?? -1;
}

/** 轮询执行历史直到出现满足条件的记录 */
async function waitExec(api: any, dagId: string, predicate: (r: any) => boolean, timeoutMs = 60_000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const r = await api.raw<{code: number; data: {records: any[]}}>(
            'GET', `/engineering/dag-executions?dagId=${dagId}&page=1&pageSize=20`);
        const recs = r.code === 200 ? (r.data?.records ?? []) : [];
        const hit = recs.find(predicate);
        if (hit) return hit;
        await new Promise((res) => setTimeout(res, 1500));
    }
    throw new Error(`等待执行历史超时: dagId=${dagId}`);
}

// ==================== 测试 ====================

test.describe('F3 方案A cron 队列控制', () => {
    let api: any;
    const cronDagIds: string[] = [];

    test.beforeAll(async () => {
        await seedF3();
        api = await loginAdmin();
    });

    test.afterAll(async () => {
        await api?.dispose();
        // 兜底清理：注销本套件遗留的 PowerJob cron job（DAG 被物理删后 job 会孤立）
        const {execSync} = await import('child_process');
        try {
            const out = execSync(
                `python -c "import urllib.request,json; r=urllib.request.urlopen(urllib.request.Request('http://localhost:7700/openApi/fetchAllJob?appId=${PJ_APP_ID}', method='POST')); [print(j['id']) for j in json.loads(r.read().decode())['data'] if str(j.get('jobName','')).startswith('${PREFIX}')]"`,
                {encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024});
            for (const jid of out.trim().split(/\s+/).filter(Boolean)) {
                execSync(
                    `python -c "import urllib.request; urllib.request.urlopen(urllib.request.Request('http://localhost:7700/openApi/deleteJob?appId=${PJ_APP_ID}&jobId=${jid}', method='POST'))"`,
                    {encoding: 'utf-8'});
            }
        } catch (e) {
            console.warn('[f3c cleanup] PowerJob job 清理异常（可忽略）:', e);
        }
        await cleanupF3();
    });

    test('H1 cron 到点（空队列）→ SCHEDULED 执行 RUNNING→SUCCESS', async () => {
        const name = `${PREFIX}ok_${Date.now() % 100000}`;
        const dag = await createCronDag(api, name);
        cronDagIds.push(dag.id);

        // 1) cron job 已注册（scheduler_job_id 落库）
        await expect.poll(async () => dagSchedulerJobId(dag.id)).toBeTruthy();
        const jobId = dagSchedulerJobId(dag.id);
        expect(jobId).toMatch(/^\d+$/);

        // 2) 模拟 cron 到点：runJob 触发
        await triggerPowerJobJob(jobId);

        // 3) SCHEDULED 执行产生并到达终态（default 队列并发足，直接 RUNNING→SUCCESS）
        const rec = await waitExec(api, dag.id, (r) => r.triggerType === 'SCHEDULED' && r.status === 'SUCCESS');
        expect(rec.queueName).toBe('default');
        expect(rec.priority).toBe(2);
    });

    test('H2 cron 到点（队列满）→ SCHEDULED 入队 WAITING → 调度器补触发 SUCCESS', async () => {
        // 用 solo 队列（并发 1）+ PYTHON sleep 20s 慢节点：先手动触发占满队列，
        // cron 到点时空位未释放 → SCHEDULED 应入队 WAITING，等慢节点结束调度器补触发 → SUCCESS
        const soloName = `${PREFIX}solo_dag_${Date.now() % 100000}`;
        const solo = await createCronDagSleep(api, soloName, 'e2e_s11f3_solo', 20);
        cronDagIds.push(solo.id);

        // 手动触发占满 solo 队列（sleep 20s，期间队列满）
        const manual = await api.raw<{code: number; data: {id: string; status: string}}>(
            'POST', `/engineering/dev/dags/${solo.id}/trigger`, {});
        expect(manual.code).toBe(200);
        // 确认手动执行进入 RUNNING（占位）
        await waitExec(api, solo.id, (r) => r.triggerType === 'MANUAL' && r.status === 'RUNNING');

        // cron job 已注册
        await expect.poll(async () => dagSchedulerJobId(solo.id)).toBeTruthy();
        const jobId = dagSchedulerJobId(solo.id);

        // cron 到点触发（此时队列仍满）
        await triggerPowerJobJob(jobId);

        // 断言：存在 SCHEDULED 且 WAITING 过的记录（队列满入池）
        const waiting = await waitExec(api, solo.id, (r) => r.triggerType === 'SCHEDULED' && r.status === 'WAITING');
        expect(waiting.queueName).toBe('e2e_s11f3_solo');
        // 手动任务 sleep 20s 结束后调度器 5s 轮询补触发 → SUCCESS
        const done = await waitExec(api, solo.id, (r) => r.triggerType === 'SCHEDULED' && r.status === 'SUCCESS');
        expect(done.queueName).toBe('e2e_s11f3_solo');
    });

    test('H3 停用调度 → cron job 注销（scheduler_job_id 清空）', async () => {
        const name = `${PREFIX}stop_${Date.now() % 100000}`;
        const dag = await createCronDag(api, name, '0 0 5 * * ?', true);
        cronDagIds.push(dag.id);
        await expect.poll(async () => dagSchedulerJobId(dag.id)).toBeTruthy();

        // 停用调度
        const r = await api.raw<{code: number}>('PUT', `/engineering/dev/dags/${dag.id}`, {
            projectId: 2083083277706489857,
            name, triggerType: 'CRON', cronExpression: '0 0 5 * * ?', scheduleEnabled: false,
            queueName: 'default', priority: 2, status: 'ENABLED',
            nodes: [{
                nodeId: 'n1', nodeName: 'SQL_SELECT_1', nodeType: 'SQL',
                positionX: 0, positionY: 0,
                config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1 AS ok'}),
            }],
            edges: [],
        });
        expect(r.code).toBe(200);
        await expect.poll(async () => dagSchedulerJobId(dag.id)).toBe('');
    });

    test('H4 删除 DAG → cron job 注销', async () => {
        const name = `${PREFIX}del_${Date.now() % 100000}`;
        const dag = await createCronDag(api, name, '0 0 6 * * ?', true);
        await expect.poll(async () => dagSchedulerJobId(dag.id)).toBeTruthy();
        const jobId = dagSchedulerJobId(dag.id);

        const r = await api.raw<{code: number}>('DELETE', `/engineering/dev/dags/${dag.id}`);
        expect(r.code).toBe(200);
        // PowerJob 侧任务已注销（deleteJob 是软删除：status=99；存在且未删除的应为 0）
        const {execSync} = await import('child_process');
        const cnt = execSync(
            `docker exec -i datanest-middleware-mysql mysql -u root -proot123 -N -e "SELECT COUNT(*) FROM powerjob.job_info WHERE id=${jobId} AND status!=99;"`,
            {encoding: 'utf-8'}).trim();
        expect(cnt).toBe('0');
    });

    test('H5 执行历史页：SCHEDULED 触发方式显示「定时触发」+ 筛选可命中（UI）', async ({page}) => {
        // 复用 H1 产生的 SCHEDULED 记录（本套件串行，H1 已产生）
        const {gotoAs} = await import('../../sprint6/helpers/e2e');
        await gotoAs(page, ADMIN.username, ADMIN.password, '/engineering/dag-executions');
        // 筛选触发方式 = 定时触发（SCHEDULED），列表出现记录
        await page.getByLabel('按触发方式筛选').selectOption('SCHEDULED');
        await page.getByRole('button', {name: '查询', exact: true}).click();
        // 至少一行且该行含「定时触发」
        const rows = page.locator('.ant-table-row');
        await expect(rows.first()).toBeVisible();
        // SCHEDULED 记录显示「定时触发」中文
        const scheduledRow = rows.filter({hasText: '定时触发'}).first();
        await expect(scheduledRow).toBeVisible();
    });

    test('H6 cron 到点 → 审计日志记录 SCHEDULED 执行（AL-4 扩展）', async () => {
        // cron 触发的执行也应产生审计（SQL_QUERY/EXECUTE）——验证链路完整性
        const name = `${PREFIX}audit_${Date.now() % 100000}`;
        const dag = await createCronDag(api, name, '0 0 7 * * ?', true);
        cronDagIds.push(dag.id);
        await expect.poll(async () => dagSchedulerJobId(dag.id)).toBeTruthy();
        await triggerPowerJobJob(dagSchedulerJobId(dag.id));

        // cron 到点 → DAG 执行 SUCCESS（SQL 节点）
        await waitExec(api, dag.id, (r) => r.triggerType === 'SCHEDULED' && r.status === 'SUCCESS');
        // 审计日志出现该 DAG 的 EXECUTE 记录
        const audit = await api.raw<{code: number; data: {records: any[]}}>(
            'GET', `/system/audit-logs?page=1&pageSize=20&keyword=${name}`);
        expect(audit.code).toBe(200);
        const hit = (audit.data?.records ?? []).find((r) => r.resourceName === name);
        expect(hit).toBeTruthy();
    });
});
