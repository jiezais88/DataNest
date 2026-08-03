import {expect, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN} from '../helpers/data';
import {getProjectId, runDag, waitDagDsSynced} from '../helpers/dag';
import {createDag} from '../helpers/seed';

let admin: Api;
let projectId: string;

test.describe.configure({mode: 'serial'});

test.describe('控制流真实执行', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        projectId = getProjectId('e2e_s5_project')!;
    });

    test.afterAll(async () => {
        await admin.dispose();
    });

    test('AC-16 条件分支执行：上游 row_count>0 应命中非默认分支', async ({page: _p}) => {
        const dag = await createDag(
            admin,
            projectId,
            `e2e_s5_cond_exec_${Date.now()}`,
            [
                {
                    nodeId: 'n_up',
                    nodeName: '上游返回2行',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SQL', sqlContent: 'SELECT 1 UNION ALL SELECT 2'}
                },
                {
                    nodeId: 'n_cond', nodeName: '条件分支', nodeType: 'CONDITION', positionX: 260, positionY: 0,
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
                    nodeName: 'A分支',
                    nodeType: 'SQL',
                    positionX: 520,
                    positionY: -120,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
                {
                    nodeId: 'n_b',
                    nodeName: 'B分支',
                    nodeType: 'SQL',
                    positionX: 520,
                    positionY: 120,
                    config: {type: 'SQL', sqlContent: 'SELECT 2'}
                },
            ],
            [
                {edgeId: 'e1', sourceNodeId: 'n_up', targetNodeId: 'n_cond'},
                {edgeId: 'e2', sourceNodeId: 'n_cond', targetNodeId: 'n_a'},
                {edgeId: 'e3', sourceNodeId: 'n_cond', targetNodeId: 'n_b'},
            ],
        );
        await waitDagDsSynced(admin, String(dag.id));
        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('SUCCESS');

        const cond = result.nodes.find((n) => n.nodeId === 'n_cond');
        const nA = result.nodes.find((n) => n.nodeId === 'n_a');
        const nB = result.nodes.find((n) => n.nodeId === 'n_b');
        const nUp = result.nodes.find((n) => n.nodeId === 'n_up');

        console.log('COND_EXEC', JSON.stringify({cond, nA, nB, nUp}));
        // 条件节点求值结果
        expect(cond.status).toBe('SUCCESS');
        expect(cond.outputInfo).toContain('branchIndex');

        // 断言实际命中分支（记录结果，若默认分支恒命中则为缺陷）
        const hitB = nB?.status === 'SUCCESS' && nA?.status === 'SKIPPED';
        const hitA = nA?.status === 'SUCCESS' && nB?.status === 'SKIPPED';
        expect(hitB || hitA).toBe(true);

        // 期望语义（PRD）：row_count=2>0 应命中分支 1（n_b）。若此处失败说明默认分支恒命中，存在缺陷。
        test.info().annotations.push({
            type: 'condition-branch',
            description: `上游 row_count>0 时：n_b=${nB?.status}, n_a=${nA?.status}, cond=${cond?.outputInfo}。期望 n_b=SUCCESS（非默认分支命中）`,
        });
        expect(hitB, 'row_count>0 应命中非默认分支 n_b（若失败=默认分支恒命中缺陷）').toBe(true);

        await admin.del(`/engineering/dev/dags/${dag.id}`);
    });

    test('AC-16 条件分支执行：row_count=0 走默认分支', async () => {
        const dag = await createDag(
            admin,
            projectId,
            `e2e_s5_cond_exec_empty_${Date.now()}`,
            [
                {
                    nodeId: 'n_up',
                    nodeName: '上游返回0行',
                    nodeType: 'SQL',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SQL', sqlContent: "SELECT 1 WHERE 1=0"}
                },
                {
                    nodeId: 'n_cond', nodeName: '条件分支', nodeType: 'CONDITION', positionX: 260, positionY: 0,
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
                    nodeName: 'A分支',
                    nodeType: 'SQL',
                    positionX: 520,
                    positionY: -120,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
                {
                    nodeId: 'n_b',
                    nodeName: 'B分支',
                    nodeType: 'SQL',
                    positionX: 520,
                    positionY: 120,
                    config: {type: 'SQL', sqlContent: 'SELECT 2'}
                },
            ],
            [
                {edgeId: 'e1', sourceNodeId: 'n_up', targetNodeId: 'n_cond'},
                {edgeId: 'e2', sourceNodeId: 'n_cond', targetNodeId: 'n_a'},
                {edgeId: 'e3', sourceNodeId: 'n_cond', targetNodeId: 'n_b'},
            ],
        );
        await waitDagDsSynced(admin, String(dag.id));
        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('SUCCESS');
        const nA = result.nodes.find((n) => n.nodeId === 'n_a');
        const nB = result.nodes.find((n) => n.nodeId === 'n_b');
        // row_count=0 → 分支[1] 不满足 → 默认分支 n_a 执行，n_b 跳过
        expect(nA?.status).toBe('SUCCESS');
        expect(nB?.status).toBe('SKIPPED');
        await admin.del(`/engineering/dev/dags/${dag.id}`);
    });

    test('AC-18 子 DAG 同步执行：父 DAG 等待子 DAG 完成', async () => {
        // 子 DAG：简单 SQL
        const subDag = await createDag(
            admin, projectId, `e2e_s5_sub_exec_child_${Date.now()}`,
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
        // 父 DAG：SUB_DAG(同步) + 下游节点
        const parent = await createDag(
            admin, projectId, `e2e_s5_sub_exec_parent_${Date.now()}`,
            [
                {
                    nodeId: 'n_sd',
                    nodeName: '同步子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: subDag.id, subDagName: subDag.name, syncExecution: true}
                },
                {
                    nodeId: 'n_after',
                    nodeName: '后续节点',
                    nodeType: 'SQL',
                    positionX: 260,
                    positionY: 0,
                    config: {type: 'SQL', sqlContent: 'SELECT 1'}
                },
            ],
            [{edgeId: 'e1', sourceNodeId: 'n_sd', targetNodeId: 'n_after'}],
        );
        await waitDagDsSynced(admin, String(parent.id));
        const result = await runDag(admin, String(parent.id));
        console.log('SUB_SYNC_RESULT', JSON.stringify(result));
        expect(result.dagStatus).toBe('SUCCESS');
        const sd = result.nodes.find((n) => n.nodeId === 'n_sd');
        const after = result.nodes.find((n) => n.nodeId === 'n_after');
        // 子 DAG 节点与后续节点都成功（同步执行：父节点等待子 DAG 完成）
        expect(sd?.status).toBe('SUCCESS');
        expect(after?.status).toBe('SUCCESS');
        await admin.del(`/engineering/dev/dags/${parent.id}`);
        await admin.del(`/engineering/dev/dags/${subDag.id}`);
    });

    test('AC-18 子 DAG 异步执行：触发子 DAG 且父 DAG 成功', async () => {
        const subDag = await createDag(
            admin, projectId, `e2e_s5_sub_exec_async_child_${Date.now()}`,
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
        const parent = await createDag(
            admin, projectId, `e2e_s5_sub_exec_async_parent_${Date.now()}`,
            [
                {
                    nodeId: 'n_sd',
                    nodeName: '异步子DAG',
                    nodeType: 'SUB_DAG',
                    positionX: 0,
                    positionY: 0,
                    config: {type: 'SUB_DAG', subDagId: subDag.id, subDagName: subDag.name, syncExecution: false}
                },
            ],
            [],
        );
        await waitDagDsSynced(admin, String(parent.id));
        // 异步：触发后父 DAG 即成功，子 DAG 独立执行
        const result = await runDag(admin, String(parent.id));
        expect(result.dagStatus).toBe('SUCCESS');
        const sd = result.nodes.find((n) => n.nodeId === 'n_sd');
        expect(sd?.status).toBe('SUCCESS');
        await admin.del(`/engineering/dev/dags/${parent.id}`);
        await admin.del(`/engineering/dev/dags/${subDag.id}`);
    });
});
