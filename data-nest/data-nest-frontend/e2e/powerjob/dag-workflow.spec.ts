import {expect, test} from '@playwright/test';
import {Api} from './helpers/api';
import {waitFor} from './helpers/poll';

/**
 * PowerJob DAG 工作流固化验证（API 级）
 *
 * 夹具：PostgreSQL datanest_engineering 库已存在的测试 DAG（本 spec 只触发、不重建、不删除）。
 * 前置条件：后端服务全部 healthy；夹具 DAG 均为 ENABLED。
 *
 * 幂等约定：触发前先确认目标 DAG 无 RUNNING 中执行（等待其收敛，超时则跳过并报清晰错误）。
 * 断言全部走 gateway API（/api/engineering/**），不直连 DB。
 */

// ---------- 夹具 DAG id（告警测试项目 projectId=2084077734951243778 下） ----------
const DAG_SINGLE = '2084090692937850882';        // 告警测试：单 SQL 节点
const DAG_CONDITION = '2084467767070494721';     // E2E-条件节点多前驱：A/B → 条件C → D(命中)/E(跳过)
const DAG_SUB = '2085694953528832001';           // P4-子DAG-夹具：单 SQL 子 DAG
const DAG_PARENT_SYNC = '2085695302939521026';   // P4-父DAG-同步子DAG：SUB_DAG(syncExecution=true)
const DAG_PARENT_ASYNC = '2085695316252241921';  // P4-父DAG-异步子DAG：SUB_DAG(syncExecution=false)
const DAG_RERUN = '2084110076918484993';         // 重跑验收：成功节点 + 失败节点（失败节点必失败）

/** 重跑验收 DAG 的节点 id（前端 UUID nodeId） */
const RERUN_NODE_SUCCESS = 'n1_1785725500658'; // 成功节点 SELECT 1 AS ok
const RERUN_NODE_FAILED = 'n2_1785725519350';  // 失败节点（SQL 必然失败）

const ADMIN = {username: 'admin', password: 'admin123'};
const TERMINAL_STATUSES = ['SUCCESS', 'FAILED', 'TERMINATED'];
const TERMINAL_TIMEOUT_MS = 90_000;

interface NodeExecutionDto {
    id: string;
    nodeId: string;
    nodeName: string;
    nodeType: string;
    status: string;
    startTime: string | null;
    endTime: string | null;
    errorMessage?: string | null;
    outputInfo?: string | null;
}

interface DagExecutionDto {
    id: string;
    dagId: string;
    dagName?: string;
    status: string;
    startTime: string | null;
    endTime: string | null;
    triggerType?: string;
    nodeExecutions?: NodeExecutionDto[];
}

let admin: Api;

test.describe.configure({mode: 'serial'});

// ---------- 辅助函数 ----------

/** 单 DAG 执行历史（/dev/dags/{id}/executions，最新在前） */
async function listExecutions(dagId: string): Promise<DagExecutionDto[]> {
    return admin.get(`/engineering/dev/dags/${dagId}/executions`);
}

/** 全局执行历史最新一条（/dag-executions 分页接口） */
async function latestExecution(dagId: string): Promise<DagExecutionDto | null> {
    const page = await admin.get(`/engineering/dag-executions?dagId=${dagId}&page=1&pageSize=1`);
    const records = (page?.records ?? []) as DagExecutionDto[];
    return records.length > 0 ? records[0] : null;
}

/** 确认目标 DAG 无 RUNNING 中执行；有则等待其收敛，超时则跳过并报清晰错误 */
async function ensureNoRunning(dagId: string, dagName: string): Promise<void> {
    try {
        await waitFor(
            async () => {
                const page = await admin.get(
                    `/engineering/dag-executions?dagId=${dagId}&status=RUNNING&page=1&pageSize=1`);
                return Number(page?.total ?? 0);
            },
            (total) => total === 0,
            {timeoutMs: TERMINAL_TIMEOUT_MS, intervalMs: 3000, label: `dag ${dagId}（${dagName}）RUNNING 执行收敛`},
        );
    } catch (e) {
        test.skip(
            true,
            `DAG ${dagId}（${dagName}）存在持续 RUNNING 中的执行，90s 内未收敛，跳过本次触发以保证幂等。` +
            `请人工检查该执行是否卡死（/api/engineering/dag-executions?dagId=${dagId}&status=RUNNING）`,
        );
    }
}

/** 触发 DAG，返回执行 DTO */
async function triggerDag(dagId: string): Promise<DagExecutionDto> {
    return admin.post(`/engineering/dev/dags/${dagId}/trigger`);
}

/** 轮询指定 executionId 直至终态，返回执行 DTO（含节点状态） */
async function waitExecutionTerminal(
    dagId: string,
    executionId: string,
    opts: { timeoutMs?: number } = {},
): Promise<DagExecutionDto> {
    const {timeoutMs = TERMINAL_TIMEOUT_MS} = opts;
    return waitFor(
        async () => {
            const list = await listExecutions(dagId);
            const found = list.find((e) => String(e.id) === String(executionId));
            if (!found) throw new Error(`执行记录不存在: executionId=${executionId} dagId=${dagId}`);
            return found;
        },
        (e) => TERMINAL_STATUSES.includes(e.status),
        {timeoutMs, intervalMs: 2000, label: `execution ${executionId} 进入终态`},
    );
}

/** 触发并等待终态 */
async function runDag(dagId: string): Promise<DagExecutionDto> {
    const dto = await triggerDag(dagId);
    expect(dto.id, 'trigger 应返回 executionId').toBeTruthy();
    return waitExecutionTerminal(dagId, String(dto.id));
}

/** 从执行 DTO 中按 nodeId 取节点 */
function nodeOf(exec: DagExecutionDto, nodeId: string): NodeExecutionDto {
    const node = (exec.nodeExecutions ?? []).find((n) => n.nodeId === nodeId);
    expect(node, `执行 ${exec.id} 中应存在节点 ${nodeId}`).toBeTruthy();
    return node!;
}

// ---------- 用例 ----------

test.describe('PowerJob DAG 工作流固化验证', () => {
    test.beforeAll(async () => {
        admin = await Api.create();
        await admin.login(ADMIN.username, ADMIN.password);
    });

    test.afterAll(async () => {
        await admin.dispose();
    });

    test('单节点 DAG：触发后终态 SUCCESS', async () => {
        await ensureNoRunning(DAG_SINGLE, '告警测试');
        const exec = await runDag(DAG_SINGLE);
        console.log('SINGLE_RESULT', JSON.stringify({id: exec.id, status: exec.status}));
        expect(exec.status).toBe('SUCCESS');
        const nodes = exec.nodeExecutions ?? [];
        expect(nodes.length).toBeGreaterThan(0);
        for (const n of nodes) {
            expect(n.status, `节点 ${n.nodeName}(${n.nodeId}) 应成功`).toBe('SUCCESS');
        }
    });

    test('条件分支 DAG：终态 SUCCESS，命中分支 D=SUCCESS、未命中 E=SKIPPED', async () => {
        await ensureNoRunning(DAG_CONDITION, 'E2E-条件节点多前驱');
        const exec = await runDag(DAG_CONDITION);
        expect(exec.status).toBe('SUCCESS');

        const nA = nodeOf(exec, 'n1_a');
        const nB = nodeOf(exec, 'n2_b');
        const nC = nodeOf(exec, 'n3_c');
        const nD = nodeOf(exec, 'n4_d');
        const nE = nodeOf(exec, 'n5_e');
        console.log('COND_RESULT', JSON.stringify({
            A: nA.status, B: nB.status, C: nC.status, D: nD.status, E: nE.status,
            condOutput: nC.outputInfo,
        }));

        // 前驱与条件节点均成功
        expect(nA.status).toBe('SUCCESS');
        expect(nB.status).toBe('SUCCESS');
        expect(nC.status).toBe('SUCCESS');
        // 命中分支 D 执行成功，未命中分支 E 跳过
        expect(nD.status, '命中分支下游D应执行成功').toBe('SUCCESS');
        expect(nE.status, '未命中分支下游E应被跳过').toBe('SKIPPED');
    });

    test('子 DAG 同步：父 SUCCESS 且子 DAG 产生新的成功执行记录', async () => {
        await ensureNoRunning(DAG_PARENT_SYNC, 'P4-父DAG-同步子DAG');
        await ensureNoRunning(DAG_SUB, 'P4-子DAG-夹具');
        // 触发前快照子 DAG 最新执行 id
        const before = await latestExecution(DAG_SUB);

        const exec = await runDag(DAG_PARENT_SYNC);
        expect(exec.status).toBe('SUCCESS');
        // 同步语义：父执行内 SUB_DAG 节点应成功
        const subNode = (exec.nodeExecutions ?? []).find((n) => n.nodeType === 'SUB_DAG');
        expect(subNode, '父执行中应存在 SUB_DAG 节点').toBeTruthy();
        expect(subNode!.status).toBe('SUCCESS');

        // 子 DAG 应产生新的执行记录且成功
        const subExec = await waitFor(
            () => latestExecution(DAG_SUB),
            (e) => e != null && String(e.id) !== String(before?.id ?? '') &&
                TERMINAL_STATUSES.includes(e.status),
            {timeoutMs: TERMINAL_TIMEOUT_MS, intervalMs: 2000, label: '子 DAG 产生新执行并进入终态'},
        );
        console.log('SUB_SYNC_CHILD', JSON.stringify({before: before?.id, after: subExec!.id, status: subExec!.status}));
        expect(subExec!.status).toBe('SUCCESS');
    });

    test('子 DAG 异步：父 SUCCESS 且异步子 DAG 节点 SUCCESS', async () => {
        await ensureNoRunning(DAG_PARENT_ASYNC, 'P4-父DAG-异步子DAG');
        await ensureNoRunning(DAG_SUB, 'P4-子DAG-夹具');
        const before = await latestExecution(DAG_SUB);

        const exec = await runDag(DAG_PARENT_ASYNC);
        expect(exec.status).toBe('SUCCESS');
        const subNode = (exec.nodeExecutions ?? []).find((n) => n.nodeType === 'SUB_DAG');
        expect(subNode, '父执行中应存在 SUB_DAG 节点').toBeTruthy();
        expect(subNode!.status, '异步子 DAG 节点应成功').toBe('SUCCESS');

        // 异步语义：父不等子完成，子 DAG 执行独立收敛（宽松断言：产生新记录并终态成功）
        const subExec = await waitFor(
            () => latestExecution(DAG_SUB),
            (e) => e != null && String(e.id) !== String(before?.id ?? '') &&
                TERMINAL_STATUSES.includes(e.status),
            {timeoutMs: TERMINAL_TIMEOUT_MS, intervalMs: 2000, label: '异步子 DAG 独立执行进入终态'},
        );
        expect(subExec!.status).toBe('SUCCESS');
    });

    test('重跑失败节点：同 executionId 续跑仍 FAILED，成功节点不重跑', async () => {
        await ensureNoRunning(DAG_RERUN, '重跑验收');

        // 1. 触发：失败节点设计为必失败，整体终态 FAILED
        const first = await runDag(DAG_RERUN);
        console.log('RERUN_FIRST', JSON.stringify({
            id: first.id, status: first.status,
            nodes: (first.nodeExecutions ?? []).map((n) => ({nodeId: n.nodeId, status: n.status, startTime: n.startTime})),
        }));
        expect(first.status).toBe('FAILED');
        const successBefore = nodeOf(first, RERUN_NODE_SUCCESS);
        const failedBefore = nodeOf(first, RERUN_NODE_FAILED);
        expect(successBefore.status).toBe('SUCCESS');
        expect(failedBefore.status).toBe('FAILED');
        expect(successBefore.startTime, '成功节点应有 startTime').toBeTruthy();

        // 2. 重跑失败节点：同一 executionId 就地续跑
        const rerunDto = await admin.post<DagExecutionDto>(
            `/engineering/dev/dags/${DAG_RERUN}/executions/${first.id}/rerun-failed`);
        expect(String(rerunDto.id), '重跑应复用原 executionId（PowerJob 就地重试语义）').toBe(String(first.id));

        // 3. 等待续跑终态：失败节点仍失败（夹具设计如此）
        const second = await waitExecutionTerminal(DAG_RERUN, String(first.id));
        expect(second.status).toBe('FAILED');
        const successAfter = nodeOf(second, RERUN_NODE_SUCCESS);
        const failedAfter = nodeOf(second, RERUN_NODE_FAILED);
        expect(failedAfter.status).toBe('FAILED');
        // 4. 成功节点不重跑：结果复用，startTime 不变
        expect(successAfter.status).toBe('SUCCESS');
        expect(successAfter.startTime, '成功节点不应重跑（startTime 应保持不变）')
            .toBe(successBefore.startTime);
    });
});
