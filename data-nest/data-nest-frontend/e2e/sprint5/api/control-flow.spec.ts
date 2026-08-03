import {expect, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN} from '../helpers/data';
import {getProjectId, waitDagDsSynced} from '../helpers/dag';
import {createDag} from '../helpers/seed';

let admin: Api;
let projectId: string;

test.describe.configure({mode: 'serial'});

test.describe('DAG 控制流 API', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        projectId = getProjectId('e2e_s5_project')!;
    });

    test.afterAll(async () => {
        await admin.dispose();
    });

    // ==================== CONDITION 保存与校验 ====================

    test('AC-15 保存含 CONDITION 节点的 DAG，config 正确序列化', async () => {
        const dag = await createDag(
            admin,
            projectId,
            `e2e_s5_cond_save_${Date.now()}`,
            [
                {
                    nodeId: 'n_up',
                    nodeName: '上游SQL',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
                {
                    nodeId: 'n_cond', nodeName: '条件分支', nodeType: 'CONDITION', positionX: 200, positionY: 0,
                    config: {
                        type: 'CONDITION',
                        branches: [
                            {branchName: '默认分支', expression: 'true', nextNodeId: 'n_a'},
                            {branchName: '非空', expression: '${upstream.row_count} > 0', nextNodeId: 'n_b'},
                        ],
                    },
                },
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 400,
                    positionY: -100,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
                {
                    nodeId: 'n_b',
                    nodeName: 'B',
                    nodeType: 'SQL',
                    positionX: 400,
                    positionY: 100,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
            ],
            [
                {edgeId: 'e1', sourceNodeId: 'n_up', targetNodeId: 'n_cond'},
                {edgeId: 'e2', sourceNodeId: 'n_cond', targetNodeId: 'n_a'},
                {edgeId: 'e3', sourceNodeId: 'n_cond', targetNodeId: 'n_b'},
            ],
        );
        expect(dag.id).toBeTruthy();
        // 从详情读取 node config，验证分支序列化
        const detail = await admin.get(`/engineering/dev/dags/${dag.id}`);
        const condNode = detail.nodes.find((n: any) => n.nodeType === 'CONDITION');
        expect(condNode).toBeTruthy();
        const cfg = JSON.parse(condNode.config);
        expect(cfg.type).toBe('CONDITION');
        expect(cfg.branches).toHaveLength(2);
        expect(cfg.branches[0].expression).toBe('true');
        expect(cfg.branches[0].nextNodeId).toBe('n_a');
        expect(cfg.branches[1].expression).toBe('${upstream.row_count} > 0');
        await admin.del(`/engineering/dev/dags/${dag.id}`);
    });

    test('CONDITION 校验：少于 2 个分支 → 7104', async () => {
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_cond_1branch_${Date.now()}`,
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
                        branches: [{branchName: '默认', expression: 'true', nextNodeId: 'n_a'}]
                    })
                },
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 0,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
            ],
            edges: [{edgeId: 'e1', sourceNodeId: 'n_cond', targetNodeId: 'n_a'}],
        });
        expect(env.code).toBe(7104);
    });

    test('CONDITION 校验：分支字段缺失（空表达式）→ 7104', async () => {
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_cond_empty_${Date.now()}`,
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
                            branchName: '空分支',
                            expression: '',
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
        });
        expect(env.code).toBe(7104);
    });

    test('CONDITION 校验：nextNodeId 指向不存在的节点 → 7104', async () => {
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_cond_badref_${Date.now()}`,
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
                            nextNodeId: 'n_not_exist'
                        }]
                    })
                },
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 0,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
            ],
            edges: [{edgeId: 'e1', sourceNodeId: 'n_cond', targetNodeId: 'n_a'}],
        });
        expect(env.code).toBe(7104);
    });

    test('非法节点类型 → 非 200', async () => {
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_badtype_${Date.now()}`,
            triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {nodeId: 'n_x', nodeName: 'X', nodeType: 'FOO', positionX: 0, positionY: 0, config: '{}'},
            ],
            edges: [],
        });
        expect(env.code).not.toBe(200);
    });

    // ==================== SUB_DAG 保存与校验 ====================

    test('AC-17 保存含 SUB_DAG 节点的 DAG（同步 + 异步）', async () => {
        // 子 DAG（简单 SQL）
        const subDag = await createDag(
            admin, projectId, `e2e_s5_subdag_ref_${Date.now()}`,
            [{
                nodeId: 'n_s',
                nodeName: '子节点',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        await waitDagDsSynced(admin, String(subDag.id));

        // 父 DAG：同步执行 + 异步执行两个 SUB_DAG 节点
        const parent = await createDag(
            admin, projectId, `e2e_s5_subdag_parent_${Date.now()}`,
            [
                {
                    nodeId: 'n_sync',
                    nodeName: '同步子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: subDag.id, subDagName: subDag.name, syncExecution: true}
                },
                {
                    nodeId: 'n_async',
                    nodeName: '异步子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 200,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: subDag.id, subDagName: subDag.name, syncExecution: false}
                },
            ],
            [{edgeId: 'e1', sourceNodeId: 'n_sync', targetNodeId: 'n_async'}],
        );
        expect(parent.id).toBeTruthy();
        await waitDagDsSynced(admin, String(parent.id));

        const detail = await admin.get(`/engineering/dev/dags/${parent.id}`);
        const syncNode = detail.nodes.find((n: any) => n.nodeId === 'n_sync');
        const asyncNode = detail.nodes.find((n: any) => n.nodeId === 'n_async');
        expect(JSON.parse(syncNode.config).syncExecution).toBe(true);
        expect(JSON.parse(asyncNode.config).syncExecution).toBe(false);

        await admin.del(`/engineering/dev/dags/${parent.id}`);
        await admin.del(`/engineering/dev/dags/${subDag.id}`);
    });

    test('SUB_DAG 校验：缺少 subDagId → 7102', async () => {
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_subdag_noid_${Date.now()}`,
            triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({type: 'SUB_DAG', syncExecution: true})
                },
            ],
            edges: [],
        });
        expect(env.code).toBe(7102);
    });

    test('SUB_DAG 校验：子 DAG 不存在 → 7102', async () => {
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_subdag_none_${Date.now()}`,
            triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({
                        type: 'SUB_DAG',
                        subDagId: 9999999999999999999,
                        subDagName: '不存在',
                        syncExecution: true
                    })
                },
            ],
            edges: [],
        });
        expect(env.code).toBe(7102);
    });

    test('SUB_DAG 校验：子 DAG 未启用 → 7103', async () => {
        // 建一个 DISABLED 的子 DAG
        const subDag = await createDag(
            admin, projectId, `e2e_s5_subdag_disabled_${Date.now()}`,
            [{
                nodeId: 'n_s',
                nodeName: '子节点',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [], {status: 'DISABLED'},
        );
        const env = await admin.raw('POST', '/engineering/dev/dags', {
            projectId, name: `e2e_s5_subdag_use_disabled_${Date.now()}`,
            triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({
                        type: 'SUB_DAG',
                        subDagId: subDag.id,
                        subDagName: subDag.name,
                        syncExecution: true
                    })
                },
            ],
            edges: [],
        });
        expect(env.code).toBe(7103);
        await admin.del(`/engineering/dev/dags/${subDag.id}`);
    });

    test('AC-19 循环引用检测：A 引用 B，B 更新引用 A → 保存 B 阻断 7101', async () => {
        // A：简单 SQL
        const a = await createDag(
            admin, projectId, `e2e_s5_cycle_a_${Date.now()}`,
            [{
                nodeId: 'n_a',
                nodeName: 'A',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        await waitDagDsSynced(admin, String(a.id));
        // B：引用 A
        const b = await createDag(
            admin, projectId, `e2e_s5_cycle_b_${Date.now()}`,
            [
                {
                    nodeId: 'n_b',
                    nodeName: 'B',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 200,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: a.id, subDagName: a.name, syncExecution: true}
                },
            ],
            [{edgeId: 'e1', sourceNodeId: 'n_b', targetNodeId: 'n_sd'}],
        );
        // A 更新为引用 B → A→B→A 循环 → 阻断（PUT 需带 id，与前端一致，否则循环检测无当前 DAG 锚点）
        const env = await admin.raw('PUT', `/engineering/dev/dags/${a.id}`, {
            id: a.id, projectId, name: a.name, triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 200,
                    positionY: 0,
                    config: JSON.stringify({type: 'SUB_DAG', subDagId: b.id, subDagName: b.name, syncExecution: true})
                },
            ],
            edges: [{edgeId: 'e1', sourceNodeId: 'n_a', targetNodeId: 'n_sd'}],
        });
        expect(env.code).toBe(7101);

        await admin.del(`/engineering/dev/dags/${b.id}`);
        await admin.del(`/engineering/dev/dags/${a.id}`);
    });

    test('AC-19 循环引用：PUT 请求体不带 id 也应阻断（后端兜底 payload.setId）', async () => {
        const a = await createDag(
            admin, projectId, `e2e_s5_cycle_noid_a_${Date.now()}`,
            [{
                nodeId: 'n_a',
                nodeName: 'A',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        await waitDagDsSynced(admin, String(a.id));
        const b = await createDag(
            admin, projectId, `e2e_s5_cycle_noid_b_${Date.now()}`,
            [
                {
                    nodeId: 'n_b',
                    nodeName: 'B',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 200,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: a.id, subDagName: a.name, syncExecution: true}
                },
            ],
            [{edgeId: 'e1', sourceNodeId: 'n_b', targetNodeId: 'n_sd'}],
        );
        // 请求体不带 id（后端 update 应回填 payload.setId(id)），A→B→A 循环仍应阻断
        const env = await admin.raw('PUT', `/engineering/dev/dags/${a.id}`, {
            projectId, name: a.name, triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 200,
                    positionY: 0,
                    config: JSON.stringify({type: 'SUB_DAG', subDagId: b.id, subDagName: b.name, syncExecution: true})
                },
            ],
            edges: [{edgeId: 'e1', sourceNodeId: 'n_a', targetNodeId: 'n_sd'}],
        });
        expect(env.code).toBe(7101);

        await admin.del(`/engineering/dev/dags/${b.id}`);
        await admin.del(`/engineering/dev/dags/${a.id}`);
    });

    test('SUB_DAG 不能引用父 DAG 自身 → 7101', async () => {
        const dag = await createDag(
            admin, projectId, `e2e_s5_subdag_self_${Date.now()}`,
            [{
                nodeId: 'n_a',
                nodeName: 'A',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        // 更新 A，使其引用自身为子 DAG → 循环阻断
        const env = await admin.raw('PUT', `/engineering/dev/dags/${dag.id}`, {
            id: dag.id, projectId, name: dag.name, triggerType: 'MANUAL', status: 'ENABLED',
            nodes: [
                {
                    nodeId: 'n_a',
                    nodeName: 'A',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: JSON.stringify({type: 'SQL', sqlContent: 'SELECT 1'})
                },
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 200,
                    positionY: 0,
                    config: JSON.stringify({
                        type: 'SUB_DAG',
                        subDagId: dag.id,
                        subDagName: dag.name,
                        syncExecution: true
                    })
                },
            ],
            edges: [{edgeId: 'e1', sourceNodeId: 'n_a', targetNodeId: 'n_sd'}],
        });
        expect(env.code).toBe(7101);
        await admin.del(`/engineering/dev/dags/${dag.id}`);
    });

    test('删除引用守卫：被 SUB_DAG 引用的 DAG 无法删除 → 7009', async () => {
        const subDag = await createDag(
            admin, projectId, `e2e_s5_guard_sub_${Date.now()}`,
            [{
                nodeId: 'n_s',
                nodeName: '子',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        await waitDagDsSynced(admin, String(subDag.id));
        const parent = await createDag(
            admin, projectId, `e2e_s5_guard_parent_${Date.now()}`,
            [
                {
                    nodeId: 'n_sd',
                    nodeName: '子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: subDag.id, subDagName: subDag.name, syncExecution: true}
                },
            ],
            [],
        );
        // 删除被引用的子 DAG → 7009
        const delEnv = await admin.raw('DELETE', `/engineering/dev/dags/${subDag.id}`);
        expect(delEnv.code).toBe(7009);
        // 先删父 DAG，再删子 DAG → 成功
        await admin.del(`/engineering/dev/dags/${parent.id}`);
        await admin.del(`/engineering/dev/dags/${subDag.id}`);
    });

    test('AC-7 删除 DAG 级联删除关联告警规则', async () => {
        const {psql} = await import('../helpers/db');
        const dag = await createDag(
            admin, projectId, `e2e_s5_cascade_alert_${Date.now()}`,
            [{
                nodeId: 'n_s',
                nodeName: '节点',
                nodeType: 'SQL',
                positionX: 0,
                positionY: 0,
                config: {type: 'SQL', sqlContent: 'SELECT 1'}
            }],
            [],
        );
        await waitDagDsSynced(admin, String(dag.id));
        psql(`DELETE FROM alert_rule WHERE object_type='DAG' AND object_id=${dag.id}`);

        const adminUserId = (await admin.get('/system/users/with-email')).find((u: any) => u.username === 'admin').id;
        const rule = await admin.post('/system/alert-rules', {
            objectType: 'DAG', objectId: dag.id,
            triggerConditions: ['FAILURE'], enabled: true, userIds: [adminUserId],
        });
        expect(rule.id).toBeTruthy();

        await admin.del(`/engineering/dev/dags/${dag.id}`);
        // 删除后告警规则应被级联删除
        const after = psql(`SELECT count(*) FROM alert_rule WHERE object_type='DAG' AND object_id=${dag.id}`);
        expect(after).toBe('0');
    });
});
