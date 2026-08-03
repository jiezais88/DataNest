import {expect, test} from '@playwright/test';
import {Api} from '../helpers/api';
import {ADMIN, DORIS_TARGET, LINEAGE, TEST_USERS} from '../helpers/data';
import {psql} from '../helpers/db';
import {getProjectId, runDag, waitDagDsSynced} from '../helpers/dag';
import {createDag} from '../helpers/seed';

let admin: Api;
let analyst: Api;

test.describe('血缘可视化 API', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
        analyst = await Api.create();
        await analyst.login(TEST_USERS.analyst.username, TEST_USERS.analyst.password);
    });

    test.afterAll(async () => {
        await admin.dispose();
        await analyst.dispose();
    });

    test('AC-1 表级血缘图谱：graph 返回节点与边，当前表高亮', async () => {
        const g = await admin.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=1`);
        const nodeIds = g.nodes.map((n: any) => n.id).sort();
        expect(nodeIds).toEqual(
            [LINEAGE.t1Source, LINEAGE.t2Source, LINEAGE.t1Target, LINEAGE.t3Target].sort(),
        );
        expect(g.edges).toHaveLength(3);
        const current = g.nodes.find((n: any) => n.current === true);
        expect(current.id).toBe(LINEAGE.t1Target);
        expect(current.name).toBe(LINEAGE.t1Target);
        // database 字段拆出库名
        expect(current.database).toBe('e2e_s5_lin');
    });

    test('AC-1 graph depth 分层：depth=2 在无更深链路时不新增节点', async () => {
        const g1 = await admin.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=1`);
        const g2 = await admin.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=2`);
        expect(g1.nodes).toHaveLength(4);
        expect(g2.nodes).toHaveLength(4);
        expect(g2.edges).toHaveLength(3);
    });

    test('AC-1 graph depth 边界：0 按默认 1 处理，99 被钳制不报错', async () => {
        const g0 = await admin.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=0`);
        expect(g0.nodes).toHaveLength(4);
        const g99 = await admin.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=99`);
        expect(g99.nodes.length).toBeGreaterThanOrEqual(4);
    });

    test('AC-7 无血缘空状态：graph 只含当前表，无边', async () => {
        const g = await admin.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.orphan)}`);
        expect(g.nodes).toHaveLength(1);
        expect(g.nodes[0].id).toBe(LINEAGE.orphan);
        expect(g.nodes[0].current).toBe(true);
        expect(g.edges).toHaveLength(0);
    });

    test('AC-3 影响分析：impact 只返回下游子图', async () => {
        const g = await admin.get(`/governance/lineage/impact?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=1`);
        const nodeIds = g.nodes.map((n: any) => n.id).sort();
        // 应包含当前表 + 下游，不应包含上游
        expect(nodeIds).toEqual([LINEAGE.t1Target, LINEAGE.t3Target].sort());
        expect(g.edges).toHaveLength(1);
        expect(g.edges[0].source).toBe(LINEAGE.t1Target);
        expect(g.edges[0].target).toBe(LINEAGE.t3Target);
    });

    test('AC-3 影响分析 depth=2 不回溯上游', async () => {
        const g = await admin.get(`/governance/lineage/impact?tableName=${encodeURIComponent(LINEAGE.t1Target)}&depth=2`);
        const nodeIds = g.nodes.map((n: any) => n.id).sort();
        expect(nodeIds).toEqual([LINEAGE.t1Target, LINEAGE.t3Target].sort());
    });

    test('AC-4 溯源分析：source 返回上游子图，depth 扩展', async () => {
        const g1 = await admin.get(`/governance/lineage/source?tableName=${encodeURIComponent(LINEAGE.t3Target)}&depth=1`);
        expect(g1.nodes.map((n: any) => n.id).sort()).toEqual([LINEAGE.t3Target, LINEAGE.t1Target].sort());
        expect(g1.edges).toHaveLength(1);

        const g2 = await admin.get(`/governance/lineage/source?tableName=${encodeURIComponent(LINEAGE.t3Target)}&depth=2`);
        const nodeIds2 = g2.nodes.map((n: any) => n.id).sort();
        expect(nodeIds2).toEqual([LINEAGE.t3Target, LINEAGE.t1Target, LINEAGE.t1Source, LINEAGE.t2Source].sort());
        expect(g2.edges).toHaveLength(3);
    });

    test('AC-2/AC-6 字段级血缘：columns 返回列→列映射链路', async () => {
        const links = await admin.get(
            `/governance/lineage/columns?tableName=${encodeURIComponent(LINEAGE.t1Target)}&columnName=amount`,
        );
        expect(links).toHaveLength(2);
        const up = links.find((l: any) => l.targetTable === LINEAGE.t1Target && l.targetColumn === 'amount');
        expect(up.sourceTable).toBe(LINEAGE.t1Source);
        expect(up.sourceColumn).toBe('amount');
        const down = links.find((l: any) => l.sourceTable === LINEAGE.t1Target && l.sourceColumn === 'amount');
        expect(down.targetTable).toBe(LINEAGE.t3Target);
        expect(down.targetColumn).toBe('total_amount');
        expect(down.lineageType).toBe('SQL');
    });

    test('AC-2 字段级血缘：单条上游链路', async () => {
        const links = await admin.get(
            `/governance/lineage/columns?tableName=${encodeURIComponent(LINEAGE.t1Target)}&columnName=id`,
        );
        expect(links).toHaveLength(1);
        expect(links[0].sourceTable).toBe(LINEAGE.t1Source);
        expect(links[0].sourceColumn).toBe('id');
        expect(links[0].targetColumn).toBe('id');
    });

    test('字段级血缘：无匹配字段返回空数组', async () => {
        const links = await admin.get(
            `/governance/lineage/columns?tableName=${encodeURIComponent(LINEAGE.t1Target)}&columnName=not_exist`,
        );
        expect(links).toHaveLength(0);
    });

    test('AC-6 字段级血缘：SYNC 类型字段映射', async () => {
        const links = await admin.get(
            `/governance/lineage/columns?tableName=e2e_s5_lin.dwd_users&columnName=name`,
        );
        expect(links).toHaveLength(1);
        expect(links[0].sourceTable).toBe('e2e_s5_lin.ods_users');
        expect(links[0].sourceColumn).toBe('name');
        expect(links[0].lineageType).toBe('SYNC');
    });

    test('表级血缘：target/{table} 返回原始记录（含字段列）', async () => {
        const records = await admin.get(`/governance/lineage/target/${encodeURIComponent(LINEAGE.t1Target)}`);
        // 指向 dwd_orders 的记录：表级 2（ods_orders、ods_order_logs）+ 字段级 2（amount、id）
        expect(records).toHaveLength(4);
        const tableLevel = records.filter((r: any) => !r.sourceColumn && !r.targetColumn);
        const fieldLevel = records.filter((r: any) => r.sourceColumn || r.targetColumn);
        expect(tableLevel).toHaveLength(2);
        expect(fieldLevel.length).toBeGreaterThanOrEqual(2);
        const amountRec = fieldLevel.find((r: any) => r.targetColumn === 'amount');
        expect(amountRec.sourceColumn).toBe('amount');
        expect(amountRec.lineageType).toBe('SQL');
    });

    test('表级血缘：dag/{dagId} 按 DAG 查询', async () => {
        const records = await admin.get('/governance/lineage/dag/1');
        expect(records.length).toBeGreaterThanOrEqual(1);
    });

    test('AC-20 权限：血缘 4 角色可读（analyst 可访问）', async () => {
        const g = await analyst.get(`/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}`);
        expect(g.nodes.length).toBeGreaterThanOrEqual(1);
        const links = await analyst.get(`/governance/lineage/columns?tableName=${encodeURIComponent(LINEAGE.t1Target)}&columnName=amount`);
        expect(links.length).toBeGreaterThanOrEqual(1);
    });

    test('权限：未登录访问血缘接口返回未登录错误', async () => {
        const anon = await Api.create();
        const env = await anon.raw('GET', `/governance/lineage/graph?tableName=${encodeURIComponent(LINEAGE.t1Target)}`);
        expect(env.code).not.toBe(200);
        await anon.dispose();
    });

    test('AC-5 字段级血缘真实写入：SQL INSERT..SELECT 节点执行后落库字段级记录', async () => {
        const projectId = getProjectId('e2e_s5_project')!;
        const dagName = `e2e_s5_field_lineage_${Date.now()}`;
        const dag = await createDag(
            admin,
            projectId,
            dagName,
            [
                {
                    nodeId: 'n_fld',
                    nodeName: '字段级血缘SQL',
                    nodeType: 'SQL',
                    positionX: 100,
                    positionY: 100,
                    config: {
                        type: 'SQL',
                        sqlContent:
                            `INSERT INTO ${DORIS_TARGET} (id, amount) SELECT id, amount FROM datanest.test_source_orders`,
                    },
                },
            ],
            [],
        );
        expect(dag.id).toBeTruthy();
        await waitDagDsSynced(admin, String(dag.id));

        // 清理该 DAG 历史产生的同 DAG 血缘（只清本次执行前的）
        psql(`DELETE FROM lineage_record WHERE dag_id=${dag.id}`);

        const result = await runDag(admin, String(dag.id));
        expect(result.dagStatus).toBe('SUCCESS');

        // 验证字段级血缘落库：target 表 e2e_s5_lin_target 与 source 表 test_source_orders 的列映射
        const fieldRecs = psql(
            `SELECT source_table, source_column, target_table, target_column, lineage_type
             FROM lineage_record
             WHERE dag_id=${dag.id} AND source_column IS NOT NULL`,
        );
        expect(fieldRecs).toContain('datanest.test_source_orders|id|datanest.e2e_s5_lin_target|id|SQL');
        expect(fieldRecs).toContain('datanest.test_source_orders|amount|datanest.e2e_s5_lin_target|amount|SQL');
        // 表级记录并存
        const tableRecs = psql(
            `SELECT count(*) FROM lineage_record WHERE dag_id=${dag.id} AND source_column IS NULL`,
        );
        expect(Number(tableRecs)).toBeGreaterThanOrEqual(1);
    });
});
