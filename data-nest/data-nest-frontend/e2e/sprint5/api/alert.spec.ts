import {expect, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN} from '../helpers/data';
import {psql, scalar} from '../helpers/db';
import {Mailhog} from '../helpers/mailhog';
import {getProjectId, runDag, waitCollectHistory, waitDagDsSynced, waitSyncJobHistory} from '../helpers/dag';
import {createDag, ensureFailingCollectTask, ensureFailingSyncJob} from '../helpers/seed';
import {waitFor} from '../helpers/poll';

let admin: Api;
let mailhog: Mailhog;
let engineerUserId: string;
let govAdminUserId: string;
let syncJobId: string;
let syncJobName: string;
let collectTaskId: string;

// ====== 本地工具 ======

function userId(username: string): string {
    return scalar(`SELECT id
                   FROM sys_user
                   WHERE username = '${username}'`)!;
}

function clearAlertHistory(objectType: string, objectId: string): void {
    psql(`DELETE
          FROM alert_history
          WHERE object_type = '${objectType}'
            AND object_id = ${objectId}`);
}

function clearDagAlertHistory(dagId: string): void {
    psql(`DELETE
          FROM dag_alert_history
          WHERE execution_id IN (SELECT id FROM dag_execution WHERE dag_id = ${dagId})`);
}

function countAlertHistory(objectType: string, objectId: string, alertType: string): number {
    const r = scalar(
        `SELECT count(*)
         FROM alert_history
         WHERE object_type = '${objectType}'
           AND object_id = ${objectId}
           AND alert_type = '${alertType}'`,
    );
    return Number(r ?? '0');
}

async function createRule(
    api: Api,
    objectType: string,
    objectId: string,
    opts: { triggers?: string[]; userIds?: string[]; enabled?: boolean; timeoutMinutes?: number } = {},
): Promise<any> {
    return api.post('/system/alert-rules', {
        objectType,
        objectId,
        triggerConditions: opts.triggers ?? ['FAILURE'],
        enabled: opts.enabled ?? true,
        timeoutMinutes: opts.timeoutMinutes ?? 30,
        userIds: opts.userIds ?? [engineerUserId],
    });
}

test.describe.configure({mode: 'serial'});

test.describe('告警中心 API', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        mailhog = new Mailhog();
        await mailhog.init();
        engineerUserId = userId('s5_engineer');
        govAdminUserId = userId('s5_govadmin');
        syncJobId = await ensureFailingSyncJob(admin);
        syncJobName = scalar(`SELECT name
                              FROM sync_job
                              WHERE id = ${syncJobId}`)!;
        collectTaskId = await ensureFailingCollectTask(admin);
    });

    test.afterAll(async () => {
        await admin.dispose();
        await mailhog.dispose();
    });

    // ==================== 用户选择器 ====================

    test('AC-8 用户选择器：只返回有邮箱用户，不含无邮箱用户', async () => {
        const users = await admin.get('/system/users/with-email');
        const usernames = users.map((u: any) => u.username);
        expect(usernames).toContain('admin');
        expect(usernames).toContain('s5_engineer');
        expect(usernames).toContain('s5_govadmin');
        expect(usernames).toContain('s5_analyst');
        expect(usernames).not.toContain('s5_noemail');
        // 每条都有邮箱
        for (const u of users) {
            expect(u.email).toBeTruthy();
        }
    });

    test('AC-8 用户选择器：keyword 按用户名/邮箱模糊过滤', async () => {
        const byName = await admin.get('/system/users/with-email?keyword=gov');
        expect(byName.map((u: any) => u.username)).toEqual(['s5_govadmin']);
        const byEmail = await admin.get('/system/users/with-email?keyword=analyst@test');
        expect(byEmail.map((u: any) => u.username)).toEqual(['s5_analyst']);
    });

    // ==================== 规则 CRUD ====================

    test('AC-10 新增告警规则：DAG/SYNC_JOB/COLLECT_TASK 三类', async () => {
        const dagRule = await createRule(admin, 'DAG', '1');
        expect(dagRule.objectType).toBe('DAG');
        expect(dagRule.objectId).toBe('1');
        expect(dagRule.userIds).toEqual([engineerUserId]);
        expect(dagRule.enabled).toBe(true);
        expect(dagRule.timeoutMinutes).toBe(30);
        expect(dagRule.triggerConditions).toEqual(['FAILURE']);
        await admin.del(`/system/alert-rules/${dagRule.id}`);

        const syncRule = await createRule(admin, 'SYNC_JOB', syncJobId, {triggers: ['FAILURE', 'TIMEOUT']});
        expect(syncRule.objectName).toBe(syncJobName);
        expect(syncRule.triggerConditions).toEqual(['FAILURE', 'TIMEOUT']);
        await admin.del(`/system/alert-rules/${syncRule.id}`);

        const collectRule = await createRule(admin, 'COLLECT_TASK', collectTaskId);
        expect(collectRule.objectType).toBe('COLLECT_TASK');
        await admin.del(`/system/alert-rules/${collectRule.id}`);
    });

    test('AC-10 新增规则校验失败：非法对象类型/无触发条件/超时无阈值/无收件人 → 7202', async () => {
        const badType = await admin.raw('POST', '/system/alert-rules', {
            objectType: 'FOO', objectId: '1', triggerConditions: ['FAILURE'], userIds: [engineerUserId],
        });
        expect(badType.code).toBe(7202);

        const noTrigger = await admin.raw('POST', '/system/alert-rules', {
            objectType: 'DAG', objectId: '1', triggerConditions: [], userIds: [engineerUserId],
        });
        expect(noTrigger.code).toBe(7202);

        const timeoutNoThreshold = await admin.raw('POST', '/system/alert-rules', {
            objectType: 'DAG', objectId: '1', triggerConditions: ['TIMEOUT'], userIds: [engineerUserId],
        });
        expect(timeoutNoThreshold.code).toBe(7202);

        const noUsers = await admin.raw('POST', '/system/alert-rules', {
            objectType: 'DAG', objectId: '1', triggerConditions: ['FAILURE'], userIds: [],
        });
        expect(noUsers.code).toBe(7202);

        const invalidTrigger = await admin.raw('POST', '/system/alert-rules', {
            objectType: 'DAG', objectId: '1', triggerConditions: ['WARNING'], userIds: [engineerUserId],
        });
        expect(invalidTrigger.code).toBe(7202);
    });

    test('同一对象重复建规则：唯一约束冲突返回非 200', async () => {
        // 使用一个不存在的对象 ID（规则允许建在不存在对象上，objectName 为空）
        const dummyId = '6200000000000000001';
        psql(`DELETE
              FROM alert_rule
              WHERE object_type = 'DAG'
                AND object_id = ${dummyId}`);
        const r1 = await createRule(admin, 'DAG', dummyId);
        expect(r1.id).toBeTruthy();
        const dup = await admin.raw('POST', '/system/alert-rules', {
            objectType: 'DAG', objectId: dummyId,
            triggerConditions: ['FAILURE'], userIds: [engineerUserId],
        });
        expect(dup.code).not.toBe(200);
        psql(`DELETE
              FROM alert_rule_user
              WHERE alert_rule_id IN (SELECT id FROM alert_rule WHERE object_type = 'DAG' AND object_id = ${dummyId})`);
        psql(`DELETE
              FROM alert_rule
              WHERE object_type = 'DAG'
                AND object_id = ${dummyId}`);
    });

    test('AC-9 规则列表：分页 + objectType 过滤 + keyword', async () => {
        const r1 = await createRule(admin, 'SYNC_JOB', syncJobId);
        const r2 = await createRule(admin, 'COLLECT_TASK', collectTaskId, {triggers: ['FAILURE', 'SUCCESS']});

        const list = await admin.get(`/system/alert-rules?page=1&pageSize=10`);
        expect(list.records.length).toBeGreaterThanOrEqual(2);
        expect(Number(list.total)).toBeGreaterThanOrEqual(2);

        const syncOnly = await admin.get(`/system/alert-rules?page=1&pageSize=10&objectType=SYNC_JOB`);
        for (const r of syncOnly.records) expect(r.objectType).toBe('SYNC_JOB');

        const byKeyword = await admin.get(`/system/alert-rules?page=1&pageSize=10&keyword=${encodeURIComponent(syncJobName)}`);
        expect(byKeyword.records.length).toBeGreaterThanOrEqual(1);
        expect(byKeyword.records[0].objectName).toBe(syncJobName);

        await admin.del(`/system/alert-rules/${r1.id}`);
        await admin.del(`/system/alert-rules/${r2.id}`);
    });

    test('规则详情/编辑/启停/删除', async () => {
        const rule = await createRule(admin, 'DAG', '1', {triggers: ['FAILURE']});
        // 详情
        const detail = await admin.get(`/system/alert-rules/${rule.id}`);
        expect(detail.id).toBe(rule.id);

        // 编辑：触发条件 + 阈值 + 用户
        const updated = await admin.put(`/system/alert-rules/${rule.id}`, {
            triggerConditions: ['FAILURE', 'SUCCESS', 'TIMEOUT'],
            timeoutMinutes: 45,
            userIds: [engineerUserId, govAdminUserId],
            enabled: false,
        });
        expect(updated.triggerConditions).toEqual(['FAILURE', 'SUCCESS', 'TIMEOUT']);
        expect(updated.timeoutMinutes).toBe(45);
        expect(updated.userIds).toEqual(expect.arrayContaining([engineerUserId, govAdminUserId]));
        expect(updated.enabled).toBe(false);

        // 启停
        await admin.put(`/system/alert-rules/${rule.id}/toggle?enabled=true`);
        const afterToggle = await admin.get(`/system/alert-rules/${rule.id}`);
        expect(afterToggle.enabled).toBe(true);

        // 删除
        await admin.del(`/system/alert-rules/${rule.id}`);
        const gone = await admin.raw('GET', `/system/alert-rules/${rule.id}`);
        expect(gone.code).toBe(7201);
    });

    test('规则 users GET/PUT', async () => {
        const rule = await createRule(admin, 'DAG', '1');
        expect(await admin.get(`/system/alert-rules/${rule.id}/users`)).toEqual([engineerUserId]);
        await admin.put(`/system/alert-rules/${rule.id}/users`, [engineerUserId, govAdminUserId]);
        const users = await admin.get(`/system/alert-rules/${rule.id}/users`);
        expect(users).toHaveLength(2);
        expect(users).toEqual(expect.arrayContaining([engineerUserId, govAdminUserId]));
        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('object-options：三类对象返回可选对象列表', async () => {
        const dagOpts = await admin.get('/system/alert-rules/object-options?objectType=DAG');
        expect(dagOpts.length).toBeGreaterThan(0);
        const syncOpts = await admin.get('/system/alert-rules/object-options?objectType=SYNC_JOB');
        expect(syncOpts.map((o: any) => o.id)).toContain(syncJobId);
        const collectOpts = await admin.get('/system/alert-rules/object-options?objectType=COLLECT_TASK');
        expect(collectOpts.map((o: any) => o.id)).toContain(collectTaskId);
        // 非法类型 → 7202
        const bad = await admin.raw('GET', '/system/alert-rules/object-options?objectType=FOO');
        expect(bad.code).toBe(7202);
    });

    // ==================== 快捷入口 ====================

    test('AC-14 快捷入口：同步任务 alert-rule GET 无规则返回 null，PUT upsert 与全局同源', async () => {
        clearAlertHistory('SYNC_JOB', syncJobId);
        const none = await admin.get(`/engineering/sync-jobs/${syncJobId}/alert-rule`);
        // 后端 Result.ok(null) 序列化省略 data 字段（undefined），等价于无规则
        expect(none == null).toBe(true);

        const saved = await admin.put(`/engineering/sync-jobs/${syncJobId}/alert-rule`, {
            objectType: 'SYNC_JOB', objectId: syncJobId,
            triggerConditions: ['FAILURE', 'SUCCESS'], enabled: true, timeoutMinutes: 30,
            userIds: [engineerUserId],
        });
        expect(saved.objectName).toBe(syncJobName);
        expect(saved.triggerConditions).toEqual(['FAILURE', 'SUCCESS']);

        // 再次 GET 有规则
        const again = await admin.get(`/engineering/sync-jobs/${syncJobId}/alert-rule`);
        expect(again.id).toBe(saved.id);

        // 全局列表可见同一条
        const list = await admin.get(`/system/alert-rules?page=1&pageSize=10&objectType=SYNC_JOB&keyword=${encodeURIComponent(syncJobName)}`);
        expect(list.records.map((r: any) => r.id)).toContain(saved.id);

        // 再次 PUT 更新（upsert 语义）
        const updated = await admin.put(`/engineering/sync-jobs/${syncJobId}/alert-rule`, {
            objectType: 'SYNC_JOB', objectId: syncJobId,
            triggerConditions: ['FAILURE'], enabled: true, timeoutMinutes: 30,
            userIds: [govAdminUserId],
        });
        expect(updated.id).toBe(saved.id);
        expect(updated.triggerConditions).toEqual(['FAILURE']);
        expect(updated.userIds).toEqual([govAdminUserId]);

        await admin.del(`/system/alert-rules/${saved.id}`);
    });

    test('AC-14 快捷入口：DAG 与采集任务 alert-rule GET/PUT', async () => {
        // DAG 快捷入口
        const dagNone = await admin.get(`/engineering/dev/dags/1/alert-rule`);
        expect(dagNone == null).toBe(true);
        const dagSaved = await admin.put(`/engineering/dev/dags/1/alert-rule`, {
            objectType: 'DAG', objectId: '1',
            triggerConditions: ['FAILURE'], enabled: true, userIds: [engineerUserId],
        });
        expect(dagSaved.id).toBeTruthy();
        await admin.del(`/system/alert-rules/${dagSaved.id}`);

        // 采集任务快捷入口
        const ctNone = await admin.get(`/governance/collect-tasks/${collectTaskId}/alert-rule`);
        expect(ctNone == null).toBe(true);
        const ctSaved = await admin.put(`/governance/collect-tasks/${collectTaskId}/alert-rule`, {
            objectType: 'COLLECT_TASK', objectId: collectTaskId,
            triggerConditions: ['FAILURE'], enabled: true, userIds: [engineerUserId],
        });
        expect(ctSaved.objectName).toBeTruthy();
        await admin.del(`/system/alert-rules/${ctSaved.id}`);
    });

    // ==================== 告警历史 ====================

    test('告警历史：空列表与分页结构', async () => {
        const list = await admin.get('/system/alert-history?page=1&pageSize=10');
        expect(list).toHaveProperty('records');
        expect(list).toHaveProperty('total');
    });

    // ==================== 告警触发真实执行 ====================

    test('AC-12 同步任务失败 → alert_history(FAILURE,SUCCESS 发送状态) + MailHog 邮件', async () => {
        clearAlertHistory('SYNC_JOB', syncJobId);
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'SYNC_JOB', syncJobId, {triggers: ['FAILURE']});

        await admin.post(`/engineering/sync-jobs/${syncJobId}/execute`);
        const status = await waitSyncJobHistory(syncJobId);
        expect(status).toBe('FAILED');

        // alert_history 出现 FAILURE 记录，sendStatus=SUCCESS
        const rec = await waitFor(
            async () => scalar(
                `SELECT send_status
                 FROM alert_history
                 WHERE object_type = 'SYNC_JOB'
                   AND object_id = ${syncJobId}
                   AND alert_type = 'FAILURE'`,
            ),
            (v) => v != null,
            {timeoutMs: 30_000, label: '同步失败告警历史'},
        );
        expect(rec).toBe('SUCCESS');

        // 邮件发出：主题含任务名与「执行失败」
        await waitFor(
            async () => mailhog.find('执行失败'),
            (msgs) => msgs.length >= 1,
            {timeoutMs: 30_000, label: 'MailHog 收到失败邮件'},
        );
        const msgs = await mailhog.find('执行失败');
        expect(msgs.some((m) => m.To.some((t) => t.Mailbox === 's5.engineer'))).toBe(true);

        await admin.del(`/system/alert-rules/${rule.id}`);
        clearAlertHistory('SYNC_JOB', syncJobId);
        await mailhog.deleteAll();
    });

    test('AC-16 60 秒防重：连续两次失败仅发一次告警', async () => {
        clearAlertHistory('SYNC_JOB', syncJobId);
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'SYNC_JOB', syncJobId, {triggers: ['FAILURE']});

        await admin.post(`/engineering/sync-jobs/${syncJobId}/execute`);
        await waitSyncJobHistory(syncJobId);
        await waitFor(
            async () => countAlertHistory('SYNC_JOB', syncJobId, 'FAILURE'),
            (n) => n >= 1,
            {timeoutMs: 30_000, label: '首次告警历史'},
        );

        // 第二次触发（60s 窗口内）→ 防重，不再新增
        await admin.post(`/engineering/sync-jobs/${syncJobId}/execute`);
        await waitSyncJobHistory(syncJobId);
        await waitFor(
            async () => countAlertHistory('SYNC_JOB', syncJobId, 'FAILURE'),
            (n) => n >= 1,
            {timeoutMs: 30_000, label: '第二次执行仍触发告警（应被防重吞掉，等待终态）'},
        );
        expect(countAlertHistory('SYNC_JOB', syncJobId, 'FAILURE')).toBe(1);

        await admin.del(`/system/alert-rules/${rule.id}`);
        clearAlertHistory('SYNC_JOB', syncJobId);
        await mailhog.deleteAll();
    });

    test('AC-17 禁用规则不触发告警', async () => {
        clearAlertHistory('SYNC_JOB', syncJobId);
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'SYNC_JOB', syncJobId, {triggers: ['FAILURE'], enabled: false});

        await admin.post(`/engineering/sync-jobs/${syncJobId}/execute`);
        await waitSyncJobHistory(syncJobId);
        // 给触发留缓冲后确认无历史
        await waitFor(
            async () => countAlertHistory('SYNC_JOB', syncJobId, 'FAILURE'),
            (n) => n === 0,
            {timeoutMs: 10_000, label: '禁用规则无告警'},
        );
        expect(countAlertHistory('SYNC_JOB', syncJobId, 'FAILURE')).toBe(0);

        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('AC-17 触发条件不匹配不触发告警（只配 SUCCESS，失败不发）', async () => {
        clearAlertHistory('SYNC_JOB', syncJobId);
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'SYNC_JOB', syncJobId, {triggers: ['SUCCESS']});

        await admin.post(`/engineering/sync-jobs/${syncJobId}/execute`);
        await waitSyncJobHistory(syncJobId);
        expect(countAlertHistory('SYNC_JOB', syncJobId, 'FAILURE')).toBe(0);
        expect(countAlertHistory('SYNC_JOB', syncJobId, 'SUCCESS')).toBe(0);

        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('AC-13 采集任务失败 → alert_history + MailHog', async () => {
        clearAlertHistory('COLLECT_TASK', collectTaskId);
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'COLLECT_TASK', collectTaskId, {triggers: ['FAILURE']});

        await admin.post(`/governance/collect-tasks/${collectTaskId}/execute`);
        const status = await waitCollectHistory(collectTaskId);
        expect(status).toBe('FAILED');

        const rec = await waitFor(
            async () => scalar(
                `SELECT send_status
                 FROM alert_history
                 WHERE object_type = 'COLLECT_TASK'
                   AND object_id = ${collectTaskId}
                   AND alert_type = 'FAILURE'`,
            ),
            (v) => v != null,
            {timeoutMs: 30_000, label: '采集失败告警历史'},
        );
        expect(rec).toBe('SUCCESS');

        await waitFor(
            async () => mailhog.find('执行失败'),
            (msgs) => msgs.length >= 1,
            {timeoutMs: 30_000, label: '采集失败邮件'},
        );

        await admin.del(`/system/alert-rules/${rule.id}`);
        clearAlertHistory('COLLECT_TASK', collectTaskId);
        await mailhog.deleteAll();
    });

    test('AC-18 DAG 失败 + alert_rule → alert_history + MailHog', async () => {
        const projectId = getProjectId('e2e_s5_project')!;
        const dagName = `e2e_s5_alert_dag_fail_${Date.now()}`;
        const dag = await createDag(
            admin,
            projectId,
            dagName,
            [
                {
                    nodeId: 'n_fail',
                    nodeName: '必然失败SQL',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 100,
                    config: {type: 'SQL', sqlContent: 'SELECT * FROM e2e_s5_not_exist_table_xyz'},
                },
            ],
            [],
        );
        await waitDagDsSynced(admin, String(dag.id));
        clearDagAlertHistory(String(dag.id));
        clearAlertHistory('DAG', String(dag.id));
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'DAG', String(dag.id), {triggers: ['FAILURE']});

        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('FAILED');

        const rec = await waitFor(
            async () => scalar(
                `SELECT send_status
                 FROM alert_history
                 WHERE object_type = 'DAG'
                   AND object_id = ${dag.id}
                   AND alert_type = 'FAILURE'`,
            ),
            (v) => v != null,
            {timeoutMs: 30_000, label: 'DAG 失败告警历史'},
        );
        expect(rec).toBe('SUCCESS');

        await waitFor(
            async () => mailhog.find('执行失败'),
            (msgs) => msgs.length >= 1,
            {timeoutMs: 30_000, label: 'DAG 失败邮件'},
        );
        const msgs = await mailhog.find('执行失败');
        expect(msgs.some((m) => m.To.some((t) => t.Mailbox === 's5.engineer'))).toBe(true);

        await admin.del(`/system/alert-rules/${rule.id}`);
        await admin.del(`/engineering/dev/dags/${dag.id}`);
    });

    test('AC-11 DAG 告警兼容：无 alert_rule 时回退 dag_alert_config 全局配置', async () => {
        const projectId = getProjectId('e2e_s5_project')!;
        const dagName = `e2e_s5_alert_dag_fallback_${Date.now()}`;
        const dag = await createDag(
            admin,
            projectId,
            dagName,
            [
                {
                    nodeId: 'n_fail2',
                    nodeName: '必然失败SQL',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 100,
                    config: {type: 'SQL', sqlContent: 'SELECT * FROM e2e_s5_not_exist_table_xyz'},
                },
            ],
            [],
        );
        await waitDagDsSynced(admin, String(dag.id));
        clearDagAlertHistory(String(dag.id));
        await mailhog.deleteAll();
        // 不创建 alert_rule，依赖全局 dag_alert_config（FAILURE, 收件人 test@example.com）

        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('FAILED');

        // dag_alert_history 出现回退记录
        await waitFor(
            async () => scalar(
                `SELECT count(*)
                 FROM dag_alert_history
                 WHERE execution_id IN (SELECT id FROM dag_execution WHERE dag_id = ${dag.id})
                   AND alert_type = 'FAILURE'`,
            ),
            (n) => Number(n) >= 1,
            {timeoutMs: 30_000, label: 'dag_alert_config 回退历史'},
        );

        // Sprint 5 测试补充：回退告警也应写入统一 alert_history（告警中心历史可见，alert_rule_id 为空）
        await waitFor(
            async () => scalar(
                `SELECT send_status
                 FROM alert_history
                 WHERE object_type = 'DAG'
                   AND object_id = ${dag.id}
                   AND alert_type = 'FAILURE'`,
            ),
            (v) => v != null,
            {timeoutMs: 30_000, label: '回退告警写入 alert_history'},
        );

        await waitFor(
            async () => mailhog.find('执行失败'),
            (msgs) => msgs.length >= 1,
            {timeoutMs: 30_000, label: '回退邮件'},
        );
        const msgs = await mailhog.find('执行失败');
        expect(msgs.some((m) => m.To.some((t) => t.Mailbox === 'test' && t.Domain === 'example.com'))).toBe(true);

        await admin.del(`/engineering/dev/dags/${dag.id}`);
        await mailhog.deleteAll();
    });

    test('AC-18 DAG 成功 + alert_rule(SUCCESS) → alert_history + MailHog', async () => {
        const projectId = getProjectId('e2e_s5_project')!;
        const dagName = `e2e_s5_alert_dag_success_${Date.now()}`;
        const dag = await createDag(
            admin,
            projectId,
            dagName,
            [
                {
                    nodeId: 'n_ok',
                    nodeName: '必然成功SQL',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 100,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'},
                },
            ],
            [],
        );
        await waitDagDsSynced(admin, String(dag.id));
        clearDagAlertHistory(String(dag.id));
        clearAlertHistory('DAG', String(dag.id));
        await mailhog.deleteAll();
        const rule = await createRule(admin, 'DAG', String(dag.id), {triggers: ['SUCCESS']});

        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('SUCCESS');

        const rec = await waitFor(
            async () => scalar(
                `SELECT send_status
                 FROM alert_history
                 WHERE object_type = 'DAG'
                   AND object_id = ${dag.id}
                   AND alert_type = 'SUCCESS'`,
            ),
            (v) => v != null,
            {timeoutMs: 30_000, label: 'DAG 成功告警历史'},
        );
        expect(rec).toBe('SUCCESS');

        await waitFor(
            async () => mailhog.find('执行成功'),
            (msgs) => msgs.length >= 1,
            {timeoutMs: 30_000, label: 'DAG 成功邮件'},
        );

        await admin.del(`/system/alert-rules/${rule.id}`);
        await admin.del(`/engineering/dev/dags/${dag.id}`);
        await mailhog.deleteAll();
    });

    test('告警历史列表过滤：objectType + alertType + sendStatus', async () => {
        // 复用上一条 DAG 成功规则记录校验过滤（先构造一条直接写库的历史）
        const rule = await createRule(admin, 'DAG', '1', {triggers: ['FAILURE']});
        psql(
            `INSERT INTO alert_history (id, alert_rule_id, object_type, object_id, alert_type, recipients, send_status,
                                        sent_at)
             VALUES (6400000000000000001, ${rule.id}, 'DAG', 1, 'FAILURE', 's5.engineer@test.io', 'SUCCESS', NOW())`,
        );
        const byType = await admin.get('/system/alert-history?page=1&pageSize=10&objectType=DAG');
        expect(byType.records.length).toBeGreaterThanOrEqual(1);
        const byAlert = await admin.get('/system/alert-history?page=1&pageSize=10&alertType=FAILURE');
        expect(byAlert.records.some((r: any) => r.objectId === '1' && r.alertType === 'FAILURE')).toBe(true);
        const byStatus = await admin.get('/system/alert-history?page=1&pageSize=10&sendStatus=SUCCESS');
        expect(byStatus.records.some((r: any) => r.sendStatus === 'SUCCESS')).toBe(true);
        const byStatusFailed = await admin.get('/system/alert-history?page=1&pageSize=10&sendStatus=FAILED');
        expect(byStatusFailed.records.some((r: any) => r.sendStatus === 'FAILED')).toBe(false);
        // 历史详情含 objectName 联查
        psql(`DELETE
              FROM alert_history
              WHERE id = 6400000000000000001`);
        await admin.del(`/system/alert-rules/${rule.id}`);
    });
});
