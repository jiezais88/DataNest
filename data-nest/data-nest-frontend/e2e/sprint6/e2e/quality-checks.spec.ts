import {expect, type Page, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS, EXEC_PREFIX, EXEC_TABLE, EXEC_BAD_TABLE, POWERJOB_APP_WORKER} from '../helpers/data';
import {psql, scalar} from '../helpers/db';
import {mysqlScalar, pgScalar, doris, quiet} from '../helpers/exec-db';
import {waitFor} from '../helpers/poll';
import {gotoAs} from '../helpers/e2e';
import {PowerJobClient} from '../helpers/powerjob';

// sprint5 helpers：复用 DAG 创建 / 执行（真实执行已由 sprint5 验证可行）
import {getProjectId, waitDagDsSynced, runDag} from '../../sprint5/helpers/dag';
import {createDag} from '../../sprint5/helpers/seed';

/**
 * Sprint 8 质量检查执行 + 结果记录（执行层）E2E 测试（页面：/governance/quality-checks 质量检查历史）。
 *
 * A. 执行结果记录
 *   - MYSQL / PG 任务执行 → PARTIAL_FAILED（成功规则 + 失败规则）
 *   - 单规则执行成功 → SUCCESS（result_value 记录）；单规则失败 → FAILED（errorMessage 记录）
 *   - 历史页展示批次（触发方式 / 状态筛选）+ 批次详情（规则明细 result_value / errorMessage）
 *   - 权限：工程师可查看（页面 + API）
 *
 * B. 自动触发（AUTO_TRIGGER）
 *   - 同步任务成功触发绑定质量任务
 *   - DAG 节点成功触发绑定质量任务
 *   - 播种 AUTO_TRIGGER 批次记录 + 历史页筛选展示
 *
 * 执行是异步的（经 PowerJob 投递到 app-worker），测试用轮询 quality_check_batch 至终态断言。
 * 测试数据前缀 e2e_s6_exec，seed 由 seedExecTables/seedExecMetadata 提供执行数据源与目标表。
 */

// ==================== 固定 ID（与 seed.ts 一致） ====================
const EXEC_DS_MYSQL_ID = '9000020000000000001';
const EXEC_DS_PG_ID = '9000020000000000002';
const EXEC_TABLE_MYSQL_OK_ID = '9000020000000000011';
const EXEC_TABLE_MYSQL_BAD_ID = '9000020000000000012';
const EXEC_TABLE_PG_OK_ID = '9000020000000000021';
const EXEC_TABLE_PG_BAD_ID = '9000020000000000022';

/** 同步任务自动触发用 Doris 目标表 */
const DORIS_TARGET = 'datanest.e2e_s6_quality_target';

let admin: Api;

/** 定位当前行：按「任务名称」列精确匹配表格行 */
function rowBy(page: Page, name: string) {
    return page.locator('.ant-table-row').filter({
        has: page.locator('.ant-table-cell:first-child').getByText(name, {exact: true}),
    });
}

/**
 * 雪花 ID 上界（~2e18 < 9e18）。
 * sprint7 种子会向 quality_check_batch 写入固定 ID 批次（900007* 号段，job_id 为 NULL，
 * 比雪花 ID 大），不过滤会：1) MAX(id) 打点被它顶住，id > marker 永远不匹配新批次；
 * 2) ORDER BY id DESC LIMIT 1 永远先捞到它。查询与打点都必须排除 9e18 号段。
 */
const SNOWFLAKE_ID_MAX = '9000000000000000000';

/** 轮询批次表：等待指定任务（job_id）+ 触发方式（trigger_type，可为 null）的批次进入终态，返回批次行 */
async function waitBatch(
    jobId: string | null,
    triggerType: string | null,
    opts: { timeoutMs?: number; minId?: string } = {},
): Promise<{id: string; status: string; jobName: string}> {
    const {timeoutMs = 120_000, minId} = opts;
    const jobCond = jobId ? `job_id=${jobId}` : 'job_id IS NULL';
    const triggerCond = triggerType ? `AND trigger_type='${triggerType}'` : '';
    // minId：只接受触发后新产生的批次，避免捡到历史残留的同条件批次（如其它 spec 的单规则批次）
    const minCond = minId ? `AND id > ${minId}` : '';
    return waitFor(
        async () => {
            const id = scalar(`SELECT id FROM quality_check_batch
                                WHERE ${jobCond} ${triggerCond} ${minCond} AND id < ${SNOWFLAKE_ID_MAX}
                                ORDER BY id DESC LIMIT 1`);
            if (!id) return null;
            return {
                id,
                status: scalar(`SELECT status FROM quality_check_batch WHERE id=${id}`) ?? 'RUNNING',
                jobName: scalar(`SELECT job_name FROM quality_check_batch WHERE id=${id}`) ?? '',
            };
        },
        (b) => b != null && b.status !== 'RUNNING',
        {timeoutMs, label: `batch(job=${jobId}, trigger=${triggerType}) 进入终态`},
    ) as unknown as {id: string; status: string; jobName: string};
}

/** 当前批次表最大雪花 id（触发前打点，配合 waitBatch 的 minId 使用；排除 9e18 固定 ID 号段，原因见上） */
function batchMarker(): string {
    return scalar(`SELECT COALESCE(MAX(id),0) FROM quality_check_batch WHERE id < ${SNOWFLAKE_ID_MAX}`) ?? '0';
}

/** 轮询：等待某 job 出现指定触发方式的 AUTO_TRIGGER 批次并进入终态（自动触发为异步回调） */
async function waitAutoBatch(
    jobId: string,
    opts: { timeoutMs?: number } = {},
): Promise<{id: string; status: string}> {
    const {timeoutMs = 180_000} = opts;
    return waitFor(
        async () => {
            const id = scalar(`SELECT id FROM quality_check_batch
                                WHERE job_id=${jobId} AND trigger_type='AUTO_TRIGGER' ORDER BY id DESC LIMIT 1`);
            if (!id) return null;
            return {
                id,
                status: scalar(`SELECT status FROM quality_check_batch WHERE id=${id}`) ?? 'RUNNING',
            };
        },
        (b) => b != null && b.status !== 'RUNNING',
        {timeoutMs, label: `job ${jobId} AUTO_TRIGGER 批次进入终态`},
    ) as unknown as {id: string; status: string};
}

/** 创建一条 CUSTOM_SQL 质量规则（成功规则：查 e2e_s6_orders 行数；失败规则：查不存在表） */
async function createCustomRule(
    api: Api,
    jobId: string | null,
    name: string,
    tableId: string,
    sql: string,
    resultMetric: string,
): Promise<string> {
    const rule = await api.post('/governance/quality/rules', {
        jobId,
        name,
        type: 'CUSTOM_SQL',
        tableId,
        sqlExpression: sql,
        resultMetric,
        weight: 1,
        enabled: 1,
    });
    return String(rule.id);
}

test.describe.configure({mode: 'serial'});

// ==================== A. 执行结果记录 ====================

test.describe('Sprint 8 质量检查执行结果记录', () => {
    // MYSQL 任务（成功规则 + 失败规则）
    let mysqlJobId = '';
    let pgJobId = '';
    // 单规则（成功 / 失败）
    let mysqlRuleOkId = '';
    let mysqlRuleBadId = '';

    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 清理历史执行数据
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (SELECT id FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%' OR job_name = '单规则执行')`);
        psql(`DELETE FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%'`);
        // 单规则（job_id 为 NULL，按名称清）：被中断的运行会漏掉 afterAll，残留会导致本次建规则报「已存在同名规则」
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%') OR name LIKE '${EXEC_PREFIX}_single%'`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%'`);

        // MYSQL 任务：成功规则（count orders）+ 失败规则（count 不存在表）
        const mj = await admin.post('/governance/quality/jobs', {
            name: `${EXEC_PREFIX}_mysql_job`, description: 's8 mysql exec',
            datasourceId: EXEC_DS_MYSQL_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 0,
            alertLevel: 'SEVERE_WARNING',
        });
        mysqlJobId = String(mj.id);
        await createCustomRule(admin, mysqlJobId, `${EXEC_PREFIX}_mysql_ok_rule`,
            EXEC_TABLE_MYSQL_OK_ID, `SELECT COUNT(*) AS total FROM ${EXEC_TABLE}`, 'total');
        await createCustomRule(admin, mysqlJobId, `${EXEC_PREFIX}_mysql_bad_rule`,
            EXEC_TABLE_MYSQL_BAD_ID, `SELECT COUNT(*) AS total FROM ${EXEC_BAD_TABLE}`, 'total');

        // PG 任务：同上（PG 表名拼 public.）
        const pj = await admin.post('/governance/quality/jobs', {
            name: `${EXEC_PREFIX}_pg_job`, description: 's8 pg exec',
            datasourceId: EXEC_DS_PG_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 0,
            alertLevel: 'SEVERE_WARNING',
        });
        pgJobId = String(pj.id);
        await createCustomRule(admin, pgJobId, `${EXEC_PREFIX}_pg_ok_rule`,
            EXEC_TABLE_PG_OK_ID, `SELECT COUNT(*) AS total FROM public.${EXEC_TABLE}`, 'total');
        await createCustomRule(admin, pgJobId, `${EXEC_PREFIX}_pg_bad_rule`,
            EXEC_TABLE_PG_BAD_ID, `SELECT COUNT(*) AS total FROM public.${EXEC_BAD_TABLE}`, 'total');

        // 供单规则执行测试的规则（MYSQL 成功 + 失败，独立批次；jobId 为空表示规则独立创建）
        mysqlRuleOkId = await createCustomRule(admin, null, `${EXEC_PREFIX}_single_ok`,
            EXEC_TABLE_MYSQL_OK_ID, `SELECT COUNT(*) AS total FROM ${EXEC_TABLE}`, 'total');
        mysqlRuleBadId = await createCustomRule(admin, null, `${EXEC_PREFIX}_single_bad`,
            EXEC_TABLE_MYSQL_BAD_ID, `SELECT COUNT(*) AS total FROM ${EXEC_BAD_TABLE}`, 'total');
    });

    test.afterAll(async () => {
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (SELECT id FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%' OR job_name = '单规则执行')`);
        psql(`DELETE FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%'`);
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%') OR name LIKE '${EXEC_PREFIX}_single%'`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%'`);
        await admin.dispose();
    });

    test('页面加载：质量检查历史页 + 筛选控件', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-checks');
        await expect(page.getByRole('heading', {name: '质量检查历史'})).toBeVisible();
        await expect(page.getByLabel('按触发方式筛选')).toBeVisible();
        await expect(page.getByLabel('按状态筛选')).toBeVisible();
    });

    test('MYSQL 任务执行 → PARTIAL_FAILED（1 成功 + 1 失败）', async () => {
        await admin.post(`/governance/quality/jobs/${mysqlJobId}/execute`);
        const batch = await waitBatch(mysqlJobId, 'MANUAL');
        expect(batch.status).toBe('PARTIAL_FAILED');

        // 明细：成功规则 result_value=4（orders 4 行），失败规则 errorMessage 非空
        const okValue = scalar(`SELECT result_value FROM quality_check_detail
                                WHERE batch_id=${batch.id} AND rule_name LIKE '%_ok_rule'`);
        expect(okValue).toBe('4.000000');
        const badErr = scalar(`SELECT error_message FROM quality_check_detail
                               WHERE batch_id=${batch.id} AND rule_name LIKE '%_bad_rule'`);
        expect(badErr).toBeTruthy();
        const badSuccess = scalar(`SELECT success FROM quality_check_detail
                                   WHERE batch_id=${batch.id} AND rule_name LIKE '%_bad_rule'`);
        expect(badSuccess).toBe('0');
    });

    test('PG 任务执行 → PARTIAL_FAILED（1 成功 + 1 失败）', async () => {
        await admin.post(`/governance/quality/jobs/${pgJobId}/execute`);
        const batch = await waitBatch(pgJobId, 'MANUAL');
        expect(batch.status).toBe('PARTIAL_FAILED');
        const okValue = scalar(`SELECT result_value FROM quality_check_detail
                                WHERE batch_id=${batch.id} AND rule_name LIKE '%_ok_rule'`);
        expect(okValue).toBe('4.000000');
    });

    test('历史页展示：手动触发批次 + 状态筛选 PARTIAL_FAILED', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-checks');
        // 触发方式 = 手动
        await page.getByLabel('按触发方式筛选').selectOption('MANUAL');
        await page.getByRole('button', {name: /查询/}).click();
        // MYSQL / PG 任务批次都出现（按 job 名）
        await expect(rowBy(page, `${EXEC_PREFIX}_mysql_job`).getByText('部分失败', {exact: true}))
            .toBeVisible({timeout: 15000});
        await expect(rowBy(page, `${EXEC_PREFIX}_pg_job`).getByText('部分失败', {exact: true})).toBeVisible();
        // 成功/失败计数列（1 成功 / 1 失败）
        await expect(rowBy(page, `${EXEC_PREFIX}_mysql_job`).getByText(/1 成功 \/ 1 失败/)).toBeVisible();

        // 重置，按状态筛选 PARTIAL_FAILED
        await page.getByRole('button', {name: /重置/}).click();
        await page.getByLabel('按状态筛选').selectOption('PARTIAL_FAILED');
        await page.getByRole('button', {name: /查询/}).click();
        await expect(rowBy(page, `${EXEC_PREFIX}_mysql_job`)).toBeVisible({timeout: 15000});
    });

    test('批次详情：成功规则 result_value + 失败规则 errorMessage', async ({page}) => {
        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-checks');
        await page.getByLabel('按状态筛选').selectOption('PARTIAL_FAILED');
        await page.getByRole('button', {name: /查询/}).click();
        const row = rowBy(page, `${EXEC_PREFIX}_mysql_job`);
        await expect(row).toBeVisible({timeout: 15000});
        await row.getByLabel('查看明细').click();
        const drawer = page.getByRole('dialog', {name: `${EXEC_PREFIX}_mysql_job`});
        await drawer.waitFor({state: 'visible', timeout: 10000});
        // 批次概览：规则总数 2 / 成功 1 / 失败 1
        await expect(drawer.getByText('规则总数', {exact: true})).toBeVisible();
        const overview = drawer.locator('section').filter({hasText: '批次概览'});
        await expect(overview.getByText('2', {exact: true})).toBeVisible();
        // 成功规则：名称 + 成功徽章 + 结果值；失败规则：名称 + 失败徽章 + 错误信息
        await expect(drawer.getByText(`${EXEC_PREFIX}_mysql_ok_rule`, {exact: true})).toBeVisible();
        await expect(drawer.getByText(`${EXEC_PREFIX}_mysql_bad_rule`, {exact: true})).toBeVisible();
        await expect(drawer.getByText('成功', {exact: true}).first()).toBeVisible();
        await expect(drawer.getByText('失败', {exact: true}).first()).toBeVisible();
        // 失败规则带错误信息（表不存在）
        await expect(drawer.getByText(/校验 SQL 执行失败/)).toBeVisible();
        await drawer.getByRole('button', {name: '关闭'}).last().click();
    });

    test('单规则执行成功 → SUCCESS，result_value 记录', async () => {
        const since = batchMarker();
        await admin.post(`/governance/quality/rules/${mysqlRuleOkId}/execute`);
        const batch = await waitBatch(null, null, {minId: since});
        // 单规则批次：job_id 为空，jobName=「规则名（表名）」（executeRule 落库后按规则名+表名更新）
        expect(batch.jobName).toBe(`${EXEC_PREFIX}_single_ok（${EXEC_TABLE}）`);
        expect(batch.status).toBe('SUCCESS');
        const detail = psql(`SELECT success, result_value FROM quality_check_detail WHERE batch_id=${batch.id}`);
        // 断言行：1|4.000000
        expect(detail.split('|')[0]).toBe('1');
        expect(detail.split('|')[1]).toBe('4.000000');
    });

    test('单规则执行失败 → FAILED，errorMessage 记录', async () => {
        const since = batchMarker();
        await admin.post(`/governance/quality/rules/${mysqlRuleBadId}/execute`);
        const batch = await waitBatch(null, null, {minId: since});
        expect(batch.status).toBe('FAILED');
        const success = scalar(`SELECT success FROM quality_check_detail WHERE batch_id=${batch.id}`);
        const err = scalar(`SELECT error_message FROM quality_check_detail WHERE batch_id=${batch.id}`);
        expect(success).toBe('0');
        expect(err).toBeTruthy();
    });

    test('权限：工程师可访问质量检查历史页 + API', async ({page}) => {
        await gotoAs(page, TEST_USERS.engineer.username, TEST_USERS.engineer.password, '/governance/quality-checks');
        await expect(page.getByRole('heading', {name: '质量检查历史'})).toBeVisible();
        // API 可查询
        const engineer = await Api.create();
        await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
        const list = await engineer.post('/governance/quality/checks/page', {page: 1, pageSize: 5});
        expect(Array.isArray(list.records)).toBe(true);
        await engineer.dispose();
    });
});

// ==================== B. 自动触发（AUTO_TRIGGER） ====================

test.describe('Sprint 8 质量检查自动触发', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        // 清理历史自动触发批次
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (SELECT id FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%'`);
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%'`);
    });

    test.afterAll(async () => {
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (SELECT id FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%'`);
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%'`);
        await admin.dispose();
    });

    test('播种 AUTO_TRIGGER 批次记录 + 历史页筛选展示', async ({page}) => {
        // 播种一条 AUTO_TRIGGER 批次（job_name 带前缀），含 1 条成功明细
        psql(`INSERT INTO quality_check_batch (id, job_id, job_name, trigger_type, status, started_at, ended_at, duration_ms, created_at)
              VALUES (9000030000000000001, NULL, '${EXEC_PREFIX}_seeded_auto', 'AUTO_TRIGGER', 'SUCCESS',
                      now(), now(), 100, now())`);
        psql(`INSERT INTO quality_check_detail (id, batch_id, rule_id, rule_name, rule_type, table_id, result_metric,
                                                result_value, success, error_message, executed_sql, created_at)
              VALUES (9000030000000000101, 9000030000000000001, 1, '${EXEC_PREFIX}_seeded_rule', 'CUSTOM_SQL',
                      NULL, 'total', 5.000000, 1, NULL, 'SELECT 5', now())`);

        await gotoAs(page, TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password, '/governance/quality-checks');
        await page.getByLabel('按触发方式筛选').selectOption('AUTO_TRIGGER');
        await page.getByRole('button', {name: /查询/}).click();
        await expect(rowBy(page, `${EXEC_PREFIX}_seeded_auto`).getByText('自动触发', {exact: true}))
            .toBeVisible({timeout: 15000});
        // 批次状态徽章 = 成功（用 first 避免命中成功/失败计数列的“成功”）
        await expect(rowBy(page, `${EXEC_PREFIX}_seeded_auto`).getByText('成功', {exact: true}).first()).toBeVisible();

        // 清理播种记录
        psql(`DELETE FROM quality_check_detail WHERE batch_id=9000030000000000001`);
        psql(`DELETE FROM quality_check_batch WHERE id=9000030000000000001`);
    });

    test('同步任务成功触发绑定质量任务 → AUTO_TRIGGER 批次', async () => {
        // 确保 Doris 目标表存在
        quiet(doris, `CREATE TABLE IF NOT EXISTS ${DORIS_TARGET} (
            id BIGINT, order_no VARCHAR(64), amount DECIMAL(18,2)
        ) DISTRIBUTED BY HASH(id) BUCKETS 3 PROPERTIES ("replication_num"="1")`);

        // 建同步任务：MYSQL e2e_s6_orders → Doris datanest
        const syncJob = await admin.post('/engineering/sync-jobs', {
            name: `${EXEC_PREFIX}_sync_${Date.now()}`,
            sourceDatasourceId: EXEC_DS_MYSQL_ID,
            sourceDatabase: 'testdb',
            sourceTables: [EXEC_TABLE],
            syncMode: 'FULL',
            triggerType: 'MANUAL',
            targetDatabase: 'datanest',
            targetTable: 'e2e_s6_quality_target',
            retryTimes: 0,
            retryInterval: 5,
            rateLimitEnabled: false,
        });
        const syncJobId = String(syncJob.id);

        // 建绑定 SYNC_JOB 的质量任务（含 1 条成功规则）
        const job = await admin.post('/governance/quality/jobs', {
            name: `${EXEC_PREFIX}_auto_sync_job`, description: 's8 auto sync',
            datasourceId: EXEC_DS_MYSQL_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 1,
            autoTriggerObjectType: 'SYNC_JOB', autoTriggerObjectId: syncJobId,
            alertLevel: 'SEVERE_WARNING',
        });
        const jobId = String(job.id);
        await createCustomRule(admin, jobId, `${EXEC_PREFIX}_auto_sync_rule`,
            EXEC_TABLE_MYSQL_OK_ID, `SELECT COUNT(*) AS total FROM ${EXEC_TABLE}`, 'total');

        // 执行同步任务，等待历史进入终态
        await admin.post(`/engineering/sync-jobs/${syncJobId}/execute`);

        // 轮询 AUTO_TRIGGER 批次
        const batch = await waitAutoBatch(jobId);
        expect(batch.status).toBe('SUCCESS');
        const trigger = scalar(`SELECT trigger_type FROM quality_check_batch WHERE id=${batch.id}`);
        expect(trigger).toBe('AUTO_TRIGGER');

        // 清理同步任务及其历史/告警
        psql(`DELETE FROM sync_job_history WHERE sync_job_id=${syncJobId}`);
        psql(`DELETE FROM sync_job_log WHERE sync_job_id=${syncJobId}`);
        psql(`DELETE FROM sync_job WHERE id=${syncJobId}`);
    });

    test('DAG 节点成功触发绑定质量任务 → AUTO_TRIGGER 批次', async () => {
        const projectId = getProjectId('e2e_s5_project')!;
        // 建一个单 SQL 节点 DAG
        const dag = await createDag(
            admin, projectId, `${EXEC_PREFIX}_dag_${Date.now()}`,
            [{
                nodeId: 'n_sql', nodeName: '执行成功节点', nodeType: 'SQL',
                positionX: 0, positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'},
            }],
            [],
        );
        await waitDagDsSynced(admin, String(dag.id));

        // 反查 dag_node 主键 id
        const dagNodeId = scalar(`SELECT id FROM dag_node WHERE dag_id=${dag.id} AND node_id='n_sql'`);
        expect(dagNodeId).toBeTruthy();

        // 建绑定 DAG_NODE 的质量任务
        const job = await admin.post('/governance/quality/jobs', {
            name: `${EXEC_PREFIX}_auto_dag_job`, description: 's8 auto dag',
            datasourceId: EXEC_DS_MYSQL_ID, enabled: 1, scheduledEnabled: 0, autoTriggerEnabled: 1,
            autoTriggerObjectType: 'DAG_NODE', autoTriggerObjectId: dagNodeId,
            alertLevel: 'SEVERE_WARNING',
        });
        const jobId = String(job.id);
        await createCustomRule(admin, jobId, `${EXEC_PREFIX}_auto_dag_rule`,
            EXEC_TABLE_MYSQL_OK_ID, `SELECT COUNT(*) AS total FROM ${EXEC_TABLE}`, 'total');

        // 运行 DAG
        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('SUCCESS');

        const batch = await waitAutoBatch(jobId);
        expect(batch.status).toBe('SUCCESS');

        // 清理 DAG
        await admin.del(`/engineering/dev/dags/${dag.id}`);
    });
});

// ==================== C. 定时触发（SCHEDULED） ====================

test.describe('Sprint 8 质量检查定时触发', () => {
    // 质量任务注册到 data-nest-worker App（appId=2），processorInfo=qualityCheckExecuteHandler，jobParams=纯 jobId
    const WORKER_APP_ID = POWERJOB_APP_WORKER;
    const HANDLER = 'qualityCheckExecuteHandler';

    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (SELECT id FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%'`);
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%'`);
    });

    test.afterAll(async () => {
        psql(`DELETE FROM quality_check_detail WHERE batch_id IN (SELECT id FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_check_batch WHERE job_name LIKE '${EXEC_PREFIX}%'`);
        psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%')`);
        psql(`DELETE FROM quality_job WHERE name LIKE '${EXEC_PREFIX}%'`);
        await admin.dispose();
    });

    test('定时任务：创建即注册 PowerJob，runJob 后落 SCHEDULED 批次', async () => {
        // 建定时质量任务（cron=每天 0 点，避免立即到点；创建时自动注册 PowerJob）
        const job = await admin.post('/governance/quality/jobs', {
            name: `${EXEC_PREFIX}_scheduled_job`, description: 's8 scheduled',
            datasourceId: EXEC_DS_MYSQL_ID, enabled: 1, scheduledEnabled: 1, cron: '0 0 0 * * ?',
            autoTriggerEnabled: 0, alertLevel: 'SEVERE_WARNING',
        });
        const jobId = String(job.id);
        await createCustomRule(admin, jobId, `${EXEC_PREFIX}_scheduled_rule`,
            EXEC_TABLE_MYSQL_OK_ID, `SELECT COUNT(*) AS total FROM ${EXEC_TABLE}`, 'total');

        // 校验 PowerJob 已注册：按 processorInfo + jobParams(纯 jobId) 精确定位
        const pj = await PowerJobClient.create();
        let powerJobId = 0;
        try {
            powerJobId = await pj.findJobIdByProcessorAndParam(WORKER_APP_ID, HANDLER, jobId);
        } finally {
            await pj.dispose();
        }
        expect(powerJobId).toBeGreaterThan(0);

        // 手动触发一次（instanceParams=纯 jobId，等价定时触发；handler 对无冒号 param 按 SCHEDULED）
        const pj2 = await PowerJobClient.create();
        try {
            await pj2.runJob(powerJobId, jobId);
        } finally {
            await pj2.dispose();
        }

        // 轮询 SCHEDULED 批次进入终态，断言触发方式=定时
        const batch = await waitFor(
            async () => {
                const id = scalar(`SELECT id FROM quality_check_batch
                                    WHERE job_id=${jobId} AND trigger_type='SCHEDULED' ORDER BY id DESC LIMIT 1`);
                if (!id) return null;
                return {
                    id,
                    status: scalar(`SELECT status FROM quality_check_batch WHERE id=${id}`) ?? 'RUNNING',
                    trigger: scalar(`SELECT trigger_type FROM quality_check_batch WHERE id=${id}`) ?? '',
                };
            },
            (b) => b != null && b.status !== 'RUNNING',
            {timeoutMs: 120_000, label: 'SCHEDULED 批次进入终态'},
        ) as unknown as {id: string; status: string; trigger: string};

        expect(batch.trigger).toBe('SCHEDULED');
        expect(batch.status).toBe('SUCCESS');

        // 关闭调度（stopSchedule：停止 PowerJob，避免残留定时任务）
        await admin.post(`/governance/quality/jobs/${jobId}/schedule/stop`);
    });
});
