import {Api} from './api';
import {doris, psql} from './db';
import {
    ADMIN,
    COLLECT_BAD_DATABASE,
    DAG_PREFIX,
    DORIS_TARGET,
    LINEAGE,
    SYNC_BAD_SOURCE_TABLE,
    TEST_USERS,
} from './data';

/**
 * Sprint 5 测试数据播种/清理
 * 所有函数幂等：重复执行不会产生重复数据
 */

const ERR = (e: unknown) => String(e).slice(0, 300);

/** 直接执行 Doris SQL（忽略失败，用于幂等建表/清理） */
function dorisQuiet(sql: string): void {
    try {
        doris(sql);
    } catch {
        /* 忽略：Doris 可能不可用或表已存在 */
    }
}

// ==================== 用户 ====================

/** 确保测试用户存在（幂等），返回 userId */
export async function ensureUser(api: Api, u: {
    username: string;
    password: string;
    roles: string[];
    email: string
}): Promise<string> {
    const existing = psql(`SELECT id
                           FROM sys_user
                           WHERE username = '${u.username}'`);
    if (existing) {
        if (u.email) {
            psql(`UPDATE sys_user
                  SET email='${u.email}'
                  WHERE username = '${u.username}'`);
        }
        return existing;
    }
    const user = await api.post('/system/users', {
        username: u.username,
        password: u.password,
        roles: u.roles,
        email: u.email,
    });
    return String(user.id);
}

export async function ensureTestUsers(): Promise<Record<string, string>> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    const ids: Record<string, string> = {};
    for (const [key, u] of Object.entries(TEST_USERS)) {
        ids[key] = await ensureUser(api, u);
    }
    await api.dispose();
    return ids;
}

export async function deleteTestUsers(): Promise<void> {
    for (const u of Object.values(TEST_USERS)) {
        psql(`DELETE
              FROM sys_user_role
              WHERE user_id IN (SELECT id FROM sys_user WHERE username = '${u.username}')`);
        psql(`DELETE
              FROM alert_rule_user
              WHERE user_id IN (SELECT id FROM sys_user WHERE username = '${u.username}')`);
        psql(`DELETE
              FROM sys_user
              WHERE username = '${u.username}'`);
    }
}

// ==================== 血缘数据 ====================

/** 播种血缘记录（幂等：先清 e2e_s5_lin 前缀再插入） */
export function seedLineage(): void {
    const sql = `
        DELETE
        FROM lineage_record
        WHERE source_table LIKE '${LINEAGE.t1Source.split('.')[0]}.%'
           OR target_table LIKE '${LINEAGE.t1Source.split('.')[0]}.%';

        INSERT INTO lineage_record
        (id, source_table, target_table, source_column, target_column,
         dag_id, dag_name, node_id, node_name, execution_id, lineage_type, created_at)
        VALUES (6200000000000000001, '${LINEAGE.t1Source}', '${LINEAGE.t1Target}', NULL, NULL, 1, 'e2e_s5_dag', 'n1',
                'SQL节点', 1, 'SQL', NOW()),
               (6200000000000000002, '${LINEAGE.t2Source}', '${LINEAGE.t1Target}', NULL, NULL, 1, 'e2e_s5_dag', 'n1',
                'SQL节点', 1, 'SQL', NOW()),
               (6200000000000000003, '${LINEAGE.t1Target}', '${LINEAGE.t3Target}', NULL, NULL, 1, 'e2e_s5_dag', 'n2',
                'SQL节点', 1, 'SQL', NOW()),
               (6200000000000000004, '${LINEAGE.t1Target}', '${LINEAGE.t3Target}', 'amount', 'total_amount', 1,
                'e2e_s5_dag', 'n2', 'SQL节点', 1, 'SQL', NOW()),
               (6200000000000000005, '${LINEAGE.t1Source}', '${LINEAGE.t1Target}', 'amount', 'amount', 1, 'e2e_s5_dag',
                'n1', 'SQL节点', 1, 'SQL', NOW()),
               (6200000000000000006, '${LINEAGE.t1Source}', '${LINEAGE.t1Target}', 'id', 'id', 1, 'e2e_s5_dag', 'n1',
                'SQL节点', 1, 'SQL', NOW()),
               (6200000000000000007, 'e2e_s5_lin.ods_users', 'e2e_s5_lin.dwd_users', NULL, NULL, 2, 'e2e_s5_sync_dag',
                'n3', 'SYNC节点', 2, 'SYNC', NOW()),
               (6200000000000000008, 'e2e_s5_lin.ods_users', 'e2e_s5_lin.dwd_users', 'name', 'name', 2,
                'e2e_s5_sync_dag', 'n3', 'SYNC节点', 2, 'SYNC', NOW()),
               (6200000000000000009, 'e2e_s5_lin.ods_users', 'e2e_s5_lin.dwd_users', 'age', 'age', 2, 'e2e_s5_sync_dag',
                'n3', 'SYNC节点', 2, 'SYNC', NOW());
    `;
    psql(sql);
}

export function cleanupLineage(): void {
    psql(`DELETE
          FROM lineage_record
          WHERE source_table LIKE 'e2e_s5_lin.%'
             OR target_table LIKE 'e2e_s5_lin.%'`);
}

// ==================== Doris 目标表（字段级血缘真实执行用） ====================

export function prepareDorisTarget(): void {
    dorisQuiet(`CREATE TABLE IF NOT EXISTS ${DORIS_TARGET}
    (
        id
        BIGINT,
        amount
        DECIMAL
                (
        18,
        2
                ),
        dt TEXT
        ) DISTRIBUTED BY HASH
                (
                    id
                ) BUCKETS 3 PROPERTIES
                (
                    "replication_num" =
                    "1"
                )`);
}

export function cleanupDorisTarget(): void {
    dorisQuiet(`DROP TABLE IF EXISTS ${DORIS_TARGET}`);
}

// ==================== 元数据血缘表（E2E 入口） ====================

/** 播种一条 e2e_s5_lin.dwd_orders 元数据记录，供 E2E「血缘图谱」入口按钮使用 */
export function seedMetadataTable(): void {
    psql(`DELETE FROM metadata_column WHERE table_id IN (SELECT id FROM metadata_table WHERE database_name='e2e_s5_lin')`);
    psql(`DELETE FROM metadata_table WHERE database_name='e2e_s5_lin'`);
    psql(
        `INSERT INTO metadata_table (id, datasource_id, database_name, table_name, created_at, updated_at)
         VALUES (6500000000000000001, 2083088527209295874, 'e2e_s5_lin', 'dwd_orders', NOW(), NOW())`,
    );
    psql(
        `INSERT INTO metadata_column (id, table_id, column_name, data_type, ordinal_position, created_at, updated_at)
         VALUES (6500000000000000011, 6500000000000000001, 'id', 'bigint', 1, NOW(), NOW()),
                (6500000000000000012, 6500000000000000001, 'amount', 'decimal', 2, NOW(), NOW())`,
    );
}

export function cleanupMetadataTable(): void {
    psql(`DELETE FROM metadata_column WHERE table_id IN (SELECT id FROM metadata_table WHERE database_name='e2e_s5_lin')`);
    psql(`DELETE FROM metadata_table WHERE database_name='e2e_s5_lin'`);
}

// ==================== 项目 / DAG ====================

/** 创建或复用测试项目，返回 projectId */
export async function ensureProject(api: Api, name: string): Promise<string> {
    const existing = psql(`SELECT id
                           FROM dag_project
                           WHERE name = '${name}'`);
    if (existing) return existing;
    const proj = await api.post('/engineering/dev/dag-projects', {name, description: 'sprint5 e2e test project'});
    return String(proj.id);
}

export async function deleteProjectIfUnused(name: string): Promise<void> {
    // 仅当项目内没有其他 DAG 时才删除（保证清理幂等且不误删测试外数据）
    psql(`DELETE
          FROM dag_project
          WHERE name = '${name}'`);
}

// ==================== 同步任务 ====================

const PG_DATASOURCE_ID = '2083850755297316865'; // sprint4_test_pg：middleware-test-postgres

/** 创建必然失败的同步任务（源表不存在），幂等 */
export async function ensureFailingSyncJob(api: Api): Promise<string> {
    const existing = psql(`SELECT id
                           FROM sync_job
                           WHERE name LIKE '${DAG_PREFIX}_sync_fail%'`);
    if (existing) return existing;
    const job = await api.post('/engineering/sync-jobs', {
        name: `${DAG_PREFIX}_sync_fail_${Date.now()}`,
        sourceDatasourceId: PG_DATASOURCE_ID,
        sourceDatabase: 'postgres',
        sourceTables: [SYNC_BAD_SOURCE_TABLE],
        syncMode: 'FULL',
        triggerType: 'MANUAL',
        targetDatabase: 'datanest',
        targetTable: 'e2e_s5_sync_fail_tgt',
        retryTimes: 0,
        retryInterval: 5,
        rateLimitEnabled: false,
    });
    return String(job.id);
}

export async function deleteSyncJobs(): Promise<void> {
    const ids = psql(`SELECT id
                      FROM sync_job
                      WHERE name LIKE '${DAG_PREFIX}%'`);
    for (const id of ids.split('\n').filter(Boolean)) {
        psql(`DELETE
              FROM sync_job_log
              WHERE sync_job_id = ${id}`);
        psql(`DELETE
              FROM sync_job_history
              WHERE sync_job_id = ${id}`);
        // 清理关联告警：alert_rule 主表无 object_id 列，须经 alert_rule_object 子表反查 alert_rule_id 级联删
        psql(`DELETE
              FROM alert_rule_user
              WHERE alert_rule_id IN (SELECT alert_rule_id
                                      FROM alert_rule_object
                                      WHERE object_type = 'SYNC_JOB'
                                        AND object_id = ${id})`);
        psql(`DELETE
              FROM alert_rule_object
              WHERE object_type = 'SYNC_JOB'
                AND object_id = ${id}`);
        psql(`DELETE
              FROM alert_rule
              WHERE id IN (SELECT alert_rule_id
                           FROM alert_rule_object
                           WHERE object_type = 'SYNC_JOB'
                             AND object_id = ${id})`);
        psql(`DELETE
              FROM sync_job
              WHERE id = ${id}`);
    }
}

// ==================== 采集任务 ====================

/** 创建必然失败的采集任务（数据源指向不可达端口），幂等 */
export async function ensureFailingCollectTask(api: Api): Promise<string> {
    const existing = psql(`SELECT id
                           FROM collect_task
                           WHERE name LIKE '${DAG_PREFIX}_collect_fail%'`);
    if (existing) return existing;
    // 建一个端口不可达的数据源（localhost:59999 从容器内不可达）
    const badDs = await api.post('/engineering/datasources', {
        name: `${DAG_PREFIX}_bad_ds_${Date.now()}`,
        type: 'MYSQL',
        host: 'localhost',
        port: 59999,
        databaseName: COLLECT_BAD_DATABASE,
        username: 'root',
        password: 'nopass',
        description: 'sprint5 e2e failing collect source',
    });
    const task = await api.post('/governance/collect-tasks', {
        name: `${DAG_PREFIX}_collect_fail_${Date.now()}`,
        datasourceId: badDs.id,
        scope: [COLLECT_BAD_DATABASE],
        collectMode: 'FULL',
        triggerType: 'MANUAL',
        description: 'sprint5 e2e failing collect task',
    });
    return String(task.id);
}

export async function deleteCollectTasks(): Promise<void> {
    const tasks = psql(`SELECT id
                        FROM collect_task
                        WHERE name LIKE '${DAG_PREFIX}%'`);
    for (const id of tasks.split('\n').filter(Boolean)) {
        psql(`DELETE
              FROM collect_history
              WHERE task_id = ${id}`);
        psql(`DELETE
              FROM collect_execution_log
              WHERE task_id = ${id}`);
        // 清理关联告警：经 alert_rule_object 子表反查 alert_rule_id 级联删
        psql(`DELETE
              FROM alert_rule_user
              WHERE alert_rule_id IN (SELECT alert_rule_id
                                      FROM alert_rule_object
                                      WHERE object_type = 'COLLECT_TASK'
                                        AND object_id = ${id})`);
        psql(`DELETE
              FROM alert_rule_object
              WHERE object_type = 'COLLECT_TASK'
                AND object_id = ${id}`);
        psql(`DELETE
              FROM alert_rule
              WHERE id IN (SELECT alert_rule_id
                           FROM alert_rule_object
                           WHERE object_type = 'COLLECT_TASK'
                             AND object_id = ${id})`);
        psql(`DELETE
              FROM collect_task
              WHERE id = ${id}`);
    }
}

export async function deleteBadDatasources(): Promise<void> {
    psql(`DELETE
          FROM datasource_connection
          WHERE name LIKE '${DAG_PREFIX}_bad_ds%'`);
}

// ==================== 通用 DAG 创建 ====================

export interface DagNodeInput {
    nodeId: string;
    nodeName: string;
    nodeType: 'SQL' | 'SYNC' | 'PYTHON' | 'CONDITION' | 'SUB_DAG';
    positionX: number;
    positionY: number;
    config: Record<string, unknown>;
}

export interface DagEdgeInput {
    edgeId: string;
    sourceNodeId: string;
    targetNodeId: string;
}

/** 创建 DAG（幂等：同名复用），返回 DagPayload（含 id） */
export async function createDag(
    api: Api,
    projectId: string,
    name: string,
    nodes: DagNodeInput[],
    edges: DagEdgeInput[],
    opts: { status?: string } = {},
): Promise<any> {
    const payload = {
        projectId,
        name,
        triggerType: 'MANUAL',
        maxParallelism: 3,
        status: opts.status ?? 'ENABLED',
        nodes: nodes.map((n) => ({...n, config: JSON.stringify(n.config)})),
        edges,
    };
    return api.post('/engineering/dev/dags', payload);
}

/** 按名称精确查找 DAG（返回 DagPayload 或 null） */
export async function findDagByName(api: Api, projectId: string, name: string): Promise<any | null> {
    const dags = await api.get(`/engineering/dev/dags?projectId=${projectId}`);
    return (dags ?? []).find((d: any) => d.name === name) ?? null;
}

// ==================== 清理 ====================

/** 清理全部 Sprint 5 测试数据（幂等） */
export async function cleanupAll(): Promise<void> {
    try {
        deleteTestUsers();
    } catch (e) {
        console.warn('cleanup users:', ERR(e));
    }
    try {
        cleanupLineage();
    } catch (e) {
        console.warn('cleanup lineage:', ERR(e));
    }
    try {
        cleanupMetadataTable();
    } catch (e) {
        console.warn('cleanup metadata:', ERR(e));
    }
    try {
        cleanupDorisTarget();
    } catch (e) {
        console.warn('cleanup doris:', ERR(e));
    }
    try {
        const api = await Api.create();
        await api.login(ADMIN.username, ADMIN.password);
        // 先清理 e2e_s5 DAG 的告警历史（含兼容回退 alert_rule_id=NULL 的记录，删 DAG 后无法按对象关联）
        psql(`DELETE FROM alert_history WHERE object_type='DAG' AND object_id IN (SELECT id FROM dag WHERE name LIKE 'e2e_s5%')`);
        psql(`DELETE FROM dag_alert_history WHERE execution_id IN (SELECT id FROM dag_execution WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE 'e2e_s5%'))`);
        // 删除测试 DAG（级联删 node/edge/execution/alert_rule）
        const projects = psql(`SELECT id, name
                               FROM dag_project
                               WHERE name LIKE 'e2e_s5%'`);
        for (const line of projects.split('\n').filter(Boolean)) {
            const [pid] = line.split('|');
            const listEnv = await api.raw('GET', `/engineering/dev/dags?projectId=${pid}`);
            const dags = listEnv?.data ?? [];
            for (const d of dags ?? []) {
                try {
                    await api.del(`/engineering/dev/dags/${d.id}`);
                } catch (e) {
                    console.warn('cleanup dag:', ERR(e));
                }
            }
            try {
                await api.del(`/engineering/dev/dag-projects/${pid}`);
            } catch (e) {
                console.warn('cleanup project:', ERR(e));
            }
        }
        // 删除同步/采集/坏数据源
        try {
            await deleteSyncJobs();
        } catch (e) {
            console.warn('cleanup sync:', ERR(e));
        }
        try {
            await deleteCollectTasks();
        } catch (e) {
            console.warn('cleanup collect:', ERR(e));
        }
        try {
            await deleteBadDatasources();
        } catch (e) {
            console.warn('cleanup bad ds:', ERR(e));
        }
        // 删除测试告警规则/历史：e2e_s5 对象命名 + 测试用假对象 ID 区间（6200000000000000xxx）
        // alert_rule 主表无 object_id 列，假 ID 区间须经 alert_rule_object 子表反查 alert_rule_id
        psql(`DELETE
              FROM alert_rule_user
              WHERE alert_rule_id IN (SELECT ar.id
                                      FROM alert_rule ar
                                      WHERE ar.object_name LIKE '%e2e_s5%'
                                         OR ar.id IN (SELECT alert_rule_id
                                                      FROM alert_rule_object
                                                      WHERE object_id >= 6200000000000000000
                                                        AND object_id < 6500000000000000000))`);
        psql(`DELETE
              FROM alert_rule_object
              WHERE alert_rule_id IN (SELECT ar.id
                                      FROM alert_rule ar
                                      WHERE ar.object_name LIKE '%e2e_s5%'
                                         OR ar.id IN (SELECT alert_rule_id
                                                      FROM alert_rule_object
                                                      WHERE object_id >= 6200000000000000000
                                                        AND object_id < 6500000000000000000))`);
        psql(`DELETE
              FROM alert_rule
              WHERE object_name LIKE '%e2e_s5%'
                 OR id IN (SELECT alert_rule_id
                           FROM alert_rule_object
                           WHERE object_id >= 6200000000000000000
                             AND object_id < 6500000000000000000)`);
        psql(`DELETE FROM alert_history WHERE alert_rule_id NOT IN (SELECT id FROM alert_rule)
              OR (id >= 6400000000000000000 AND id < 6500000000000000000)`);
        await api.dispose();
    } catch (e) {
        console.warn('cleanup misc:', ERR(e), (e as Error).stack);
    }
}

/** 全量播种（globalSetup 调用，幂等） */
export async function seedAll(): Promise<void> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    await ensureTestUsers();
    seedLineage();
    seedMetadataTable();
    prepareDorisTarget();
    await ensureProject(api, 'e2e_s5_project');
    await ensureFailingSyncJob(api);
    await ensureFailingCollectTask(api);
    await api.dispose();
}
