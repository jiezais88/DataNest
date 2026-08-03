import {expect, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, TEST_USERS} from '../helpers/data';

/**
 * Sprint 5 权限矩阵（PRD §8 / AC-20）：
 * - 血缘：4 角色可读
 * - 告警查看：超管/工程师/治理员；告警编辑：超管/工程师
 * - DAG 控制流编辑：超管/工程师
 */

let admin: Api;
let engineer: Api;
let govAdmin: Api;
let analyst: Api;

async function expectCode(api: Api, method: string, path: string, body?: unknown, expectNot200 = true): Promise<void> {
    const env = await api.raw(method, path, body);
    if (expectNot200) {
        expect(env.code).not.toBe(200);
    } else {
        expect(env.code).toBe(200);
    }
}

test.describe('Sprint 5 权限矩阵', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        engineer = await Api.create();
        await engineer.login(TEST_USERS.engineer.username, TEST_USERS.engineer.password);
        govAdmin = await Api.create();
        await govAdmin.login(TEST_USERS.govAdmin.username, TEST_USERS.govAdmin.password);
        analyst = await Api.create();
        await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
    });

    test.afterAll(async () => {
        await Promise.all([admin.dispose(), engineer.dispose(), govAdmin.dispose(), analyst.dispose()]);
    });

    test('血缘可视化：4 角色均可读（含分析师）', async () => {
        const path = '/governance/lineage/graph?tableName=e2e_s5_lin.dwd_orders';
        for (const api of [admin, engineer, govAdmin, analyst]) {
            const env = await api.raw('GET', path);
            expect(env.code).toBe(200);
            expect(env.data.nodes.length).toBeGreaterThanOrEqual(1);
        }
    });

    test('血缘可视化：未登录 → 401（非 200）', async () => {
        const anon = await Api.create();
        const env = await anon.raw('GET', '/governance/lineage/graph?tableName=x.y');
        expect(env.code).not.toBe(200);
        await anon.dispose();
    });

    test('告警查看（列表/详情/users/object-options/history/with-email/快捷GET）：治理员可，分析师 403', async () => {
        // 建一条规则供详情/users 用
        const {psql} = await import('../helpers/db');
        const dummyId = '6200000000000000012';
        psql(`DELETE FROM alert_rule WHERE object_type='DAG' AND object_id=${dummyId}`);
        const rule = await admin.post('/system/alert-rules', {
            objectType: 'DAG', objectId: dummyId,
            triggerConditions: ['FAILURE'], enabled: true, userIds: ['1'],
        });
        const viewPaths = [
            '/system/alert-rules?page=1&pageSize=10',
            `/system/alert-rules/${rule.id}`,
            `/system/alert-rules/${rule.id}/users`,
            '/system/alert-rules/object-options?objectType=DAG',
            '/system/alert-history?page=1&pageSize=10',
            '/system/users/with-email',
        ];
        for (const path of viewPaths) {
            const gov = await govAdmin.raw('GET', path);
            expect(gov.code, `治理员访问 ${path}`).toBe(200);
            const ana = await analyst.raw('GET', path);
            expect(ana.code, `分析师访问 ${path}`).not.toBe(200);
        }
        // 快捷入口 GET：治理员可，分析师 403
        const quickPaths = [
            '/engineering/sync-jobs/1/alert-rule',
            '/engineering/dev/dags/1/alert-rule',
            '/governance/collect-tasks/1/alert-rule',
        ];
        for (const path of quickPaths) {
            const gov = await govAdmin.raw('GET', path);
            expect(gov.code, `治理员 GET ${path}`).toBe(200);
            const ana = await analyst.raw('GET', path);
            expect(ana.code, `分析师 GET ${path}`).not.toBe(200);
        }
        await admin.del(`/system/alert-rules/${rule.id}`);
    });

    test('告警编辑（create/update/toggle/users/删除）：工程师可，治理员 403，分析师 403', async () => {
        const {psql} = await import('../helpers/db');
        const dummyId = '6200000000000000011';
        psql(`DELETE FROM alert_rule_user WHERE alert_rule_id IN (SELECT id FROM alert_rule WHERE object_type='DAG' AND object_id=${dummyId})`);
        psql(`DELETE FROM alert_rule WHERE object_type='DAG' AND object_id=${dummyId}`);
        const body = {
            objectType: 'DAG', objectId: dummyId,
            triggerConditions: ['FAILURE'], enabled: true, userIds: [],
        };
        // 工程师可创建
        const engCreate = await engineer.raw('POST', '/system/alert-rules', {
            ...body,
            userIds: [String((await engineer.get('/system/users/with-email')).find((u: any) => u.username === 's5_engineer').id)]
        });
        expect(engCreate.code).toBe(200);
        const ruleId = engCreate.data.id;
        // 治理员/分析师创建 → 403
        expect((await govAdmin.raw('POST', '/system/alert-rules', body)).code).not.toBe(200);
        expect((await analyst.raw('POST', '/system/alert-rules', body)).code).not.toBe(200);
        // 治理员/分析师更新/删除/toggle/users → 403
        expect((await govAdmin.raw('PUT', `/system/alert-rules/${ruleId}`, body)).code).not.toBe(200);
        expect((await govAdmin.raw('PUT', `/system/alert-rules/${ruleId}/toggle?enabled=false`)).code).not.toBe(200);
        expect((await govAdmin.raw('PUT', `/system/alert-rules/${ruleId}/users`, [])).code).not.toBe(200);
        expect((await govAdmin.raw('DELETE', `/system/alert-rules/${ruleId}`)).code).not.toBe(200);
        expect((await analyst.raw('DELETE', `/system/alert-rules/${ruleId}`)).code).not.toBe(200);
        // 工程师可删除
        expect((await engineer.raw('DELETE', `/system/alert-rules/${ruleId}`)).code).toBe(200);
    });

    test('快捷入口 PUT：工程师可，治理员 403', async () => {
        const quickPaths = [
            ['/engineering/sync-jobs/1/alert-rule', 'SYNC_JOB'],
            ['/engineering/dev/dags/1/alert-rule', 'DAG'],
            ['/governance/collect-tasks/1/alert-rule', 'COLLECT_TASK'],
        ];
        for (const [path, type] of quickPaths) {
            const body = {
                objectType: type, objectId: '1',
                triggerConditions: ['FAILURE'], enabled: true, userIds: [],
            };
            const gov = await govAdmin.raw('PUT', path, body);
            expect(gov.code, `治理员 PUT ${path}`).not.toBe(200);
            const ana = await analyst.raw('PUT', path, body);
            expect(ana.code, `分析师 PUT ${path}`).not.toBe(200);
        }
    });

    test('DAG 控制流编辑：工程师可创建/删除，治理员与分析师 403', async () => {
        const projects = await admin.get('/engineering/dev/dag-projects');
        const projectId = (projects.records ?? projects).find((p: any) => p.name === 'e2e_s5_project')?.id;
        const payload = {
            projectId,
            name: `e2e_s5_perm_${Date.now()}`,
            triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_cond',
                    nodeName: '条件',
                    nodeType: 'CONDITION',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({
                        type: 'CONDITION',
                        branches: [{branchName: '默认', expression: 'true', nextNodeId: 'n_a'}, {
                            branchName: '分支',
                            expression: 'true',
                            nextNodeId: 'n_b'
                        }]
                    })
                },
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: -100,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
                {
                    nodeId: 'n_b',
                    nodeName: 'B',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 100,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
            ],
            edges: [
                {edgeId: 'e1', sourceNodeId: 'n_cond', targetNodeId: 'n_a'},
                {edgeId: 'e2', sourceNodeId: 'n_cond', targetNodeId: 'n_b'},
            ],
        };
        // 工程师可创建（含 CONDITION）
        const engCreate = await engineer.raw('POST', '/engineering/dev/dags', payload);
        expect(engCreate.code).toBe(200);
        const dagId = engCreate.data.id;
        // 治理员/分析师不可创建
        expect((await govAdmin.raw('POST', '/engineering/dev/dags', payload)).code).not.toBe(200);
        expect((await analyst.raw('POST', '/engineering/dev/dags', payload)).code).not.toBe(200);
        // 治理员/分析师不可删除
        expect((await govAdmin.raw('DELETE', `/engineering/dev/dags/${dagId}`)).code).not.toBe(200);
        expect((await analyst.raw('DELETE', `/engineering/dev/dags/${dagId}`)).code).not.toBe(200);
        // 工程师可删除
        expect((await engineer.raw('DELETE', `/engineering/dev/dags/${dagId}`)).code).toBe(200);
    });

    test('DAG 列表/详情：4 角色可读', async () => {
        for (const api of [admin, engineer, govAdmin, analyst]) {
            const env = await api.raw('GET', '/engineering/dev/dags');
            expect(env.code).toBe(200);
        }
    });
});
