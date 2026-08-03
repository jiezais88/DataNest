import {Api} from './api';
import {psql, rows, scalar} from './db';
import {waitFor} from './poll';

/** 按名称查询项目 id（不存在返回 null） */
export function getProjectId(name: string): string | null {
    return scalar(`SELECT id FROM dag_project WHERE name='${name}'`);
}

/** 等待 DAG 完成 DS 同步（获得 dsProcessDefinitionCode） */
export async function waitDagDsSynced(api: Api, dagId: string, opts: { timeoutMs?: number } = {}): Promise<any> {
    const {timeoutMs = 60_000} = opts;
    return waitFor(
        async () => api.get(`/engineering/dev/dags/${dagId}`),
        (d) => d.dsProcessDefinitionCode != null,
        {timeoutMs, label: `dag ${dagId} DS 同步`},
    );
}

/** 清理指定执行相关的 node_execution / dag_execution（用于执行类测试的数据清理兜底） */
export function deleteExecution(executionId: string): void {
    psql(`DELETE FROM node_execution WHERE execution_id=${executionId}`);
    psql(`DELETE FROM dag_execution WHERE id=${executionId}`);
}

export interface NodeExecutionRow {
    nodeId: string;
    nodeType: string;
    status: string;
    errorMessage: string | null;
    outputInfo: string | null;
}

export interface DagExecutionResult {
    executionId: string;
    dagStatus: string;
    nodes: NodeExecutionRow[];
}

/** 触发 DAG 执行，返回 DagExecutionDTO */
export async function triggerDag(api: Api, dagId: string, params?: Record<string, unknown>): Promise<any> {
    return api.post(`/engineering/dev/dags/${dagId}/trigger`, params);
}

/** 轮询 dag_execution 直至进入终态，返回执行结果（含节点状态） */
export async function waitDagExecution(
    executionId: string,
    opts: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<DagExecutionResult> {
    const {timeoutMs = 180_000, intervalMs = 3000} = opts;
    return waitFor(
        async () => {
            const dagStatus = scalar(`SELECT status FROM dag_execution WHERE id=${executionId}`) ?? 'RUNNING';
            const nodeRows = rows(
                `SELECT node_id, node_type, status, COALESCE(error_message,''), COALESCE(output_info,'')
                 FROM node_execution WHERE execution_id=${executionId} ORDER BY id`,
            );
            return {
                executionId,
                dagStatus,
                nodes: nodeRows.map((r) => ({
                    nodeId: r[0],
                    nodeType: r[1],
                    status: r[2],
                    errorMessage: r[3] || null,
                    outputInfo: r[4] || null,
                })),
            };
        },
        (r) => r.dagStatus !== 'RUNNING',
        {timeoutMs, intervalMs, label: `dag execution ${executionId} 进入终态`},
    );
}

/** 触发并等待终态 */
export async function runDag(
    api: Api,
    dagId: string,
    params?: Record<string, unknown>,
    opts?: { timeoutMs?: number },
): Promise<DagExecutionResult> {
    const dto = await triggerDag(api, dagId, params);
    return waitDagExecution(String(dto.id), opts);
}

/** 等待同步任务历史进入终态，返回 sync_job_history 状态 */
export async function waitSyncJobHistory(syncJobId: string, opts: { timeoutMs?: number } = {}): Promise<string> {
    const {timeoutMs = 120_000} = opts;
    return waitFor(
        async () => scalar(`SELECT status FROM sync_job_history WHERE sync_job_id=${syncJobId} ORDER BY start_time DESC LIMIT 1`),
        (s) => s != null && s !== 'RUNNING',
        {timeoutMs, label: `sync job ${syncJobId} 历史进入终态`},
    );
}

/** 等待采集任务历史进入终态，返回状态 */
export async function waitCollectHistory(taskId: string, opts: { timeoutMs?: number } = {}): Promise<string> {
    const {timeoutMs = 120_000} = opts;
    return waitFor(
        async () => scalar(`SELECT status FROM collect_history WHERE task_id=${taskId} ORDER BY started_at DESC LIMIT 1`),
        (s) => s != null && s !== 'RUNNING',
        {timeoutMs, label: `collect task ${taskId} 历史进入终态`},
    );
}
