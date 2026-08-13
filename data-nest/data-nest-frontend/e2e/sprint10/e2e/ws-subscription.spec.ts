import {expect, test} from '@playwright/test';
import WebSocket from 'ws';
import {
    F4_DATA_PREFIX,
    WS_URL,
    cleanupF4SourceData,
    createKey,
    deleteKey,
    findRunningPipeline,
    mysqlExec,
    type F4Pipeline,
} from './helpers/f4-seed';

/**
 * Sprint 10 F4 E2E：WebSocket 实时订阅端到端（真实 CDC 链路）。
 *
 * 覆盖 AC-10：订阅后源表变更 → 10s 内经 Kafka fan-out 收到归一化事件。
 * - 握手认证（X-API-Key，无/错 Key 拒连 1002）
 * - 订阅成功（subscribed）/ 未绑定管道 9005
 * - 端到端（INSERT → INSERT 事件，字段校验）
 *
 * 前置：需 RUNNING 的 CDC 管道（`e2e_s10_f4_` 前缀，含事件作业）。缺失时用例 skip（不误报失败）。
 * 订阅方为业务端（Node.js `ws` 库），浏览器原生 WebSocket 无法自定义 X-API-Key 头。
 */

test.describe.configure({mode: 'serial'});

let pipeline: F4Pipeline | null = null;
let key: {id: string; apiKey: string} | null = null;

test.beforeAll(async () => {
    pipeline = await findRunningPipeline();
    if (pipeline) key = await createKey([pipeline.id]);
});

test.afterAll(async () => {
    if (key) await deleteKey(key.id);
    if (pipeline) cleanupF4SourceData(pipeline.sourceTable);
});

type WsMessage = Record<string, unknown>;

/** 打开会话：返回 ws 与已收消息；message 事件持续累积 */
function openSession(apiKey: string): Promise<{ws: WebSocket; messages: WsMessage[]}> {
    return new Promise((resolve, reject) => {
        const messages: WsMessage[] = [];
        const ws = new WebSocket(WS_URL, {headers: {'X-API-Key': apiKey}});
        ws.on('open', () => resolve({ws, messages}));
        ws.on('error', reject);
        ws.on('message', (data) => {
            try {
                messages.push(JSON.parse(data.toString()) as WsMessage);
            } catch {
                // 忽略非 JSON 消息
            }
        });
    });
}

/** 轮询等待满足 predicate 的消息；超时返回 null */
async function waitMessage(
    messages: WsMessage[],
    pred: (m: WsMessage) => boolean,
    timeoutMs = 10000,
): Promise<WsMessage | null> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        const hit = messages.find(pred);
        if (hit) return hit;
        await new Promise((r) => setTimeout(r, 100));
    }
    return null;
}

/** 连接并等待首个 close（拒连场景）；返回 {events, closeCode} */
function connectExpectClose(apiKey?: string): Promise<{closeCode: number; gotSubscribed: boolean}> {
    return new Promise((resolve) => {
        const ws = new WebSocket(WS_URL, apiKey ? {headers: {'X-API-Key': apiKey}} : undefined);
        let gotSubscribed = false;
        let closeCode = -1;
        const timer = setTimeout(() => {
            ws.terminate();
            resolve({closeCode, gotSubscribed});
        }, 8000);
        ws.on('message', (data) => {
            try {
                const m = JSON.parse(data.toString()) as WsMessage;
                if (m.op === 'subscribed') gotSubscribed = true;
            } catch {
                // ignore
            }
        });
        ws.on('close', (code) => {
            closeCode = code;
            clearTimeout(timer);
            resolve({closeCode, gotSubscribed});
        });
    });
}

// ==================== 用例 ====================

test('WS-1 握手认证：无 Key / 错 Key 拒连（1002），未收到 subscribed', async () => {
    const none = await connectExpectClose(undefined);
    expect(none.closeCode).toBe(1002);
    expect(none.gotSubscribed).toBe(false);

    const wrong = await connectExpectClose('wrong-key-not-exist');
    expect(wrong.closeCode).toBe(1002);
    expect(wrong.gotSubscribed).toBe(false);
});

test('WS-2 订阅成功：正确 Key 绑定管道 → subscribed', async () => {
    test.skip(!pipeline || !key, '无 RUNNING 管道或 Key');
    const {ws, messages} = await openSession(key!.apiKey);
    ws.send(JSON.stringify({op: 'subscribe', pipelineId: pipeline!.id}));
    const sub = await waitMessage(messages, (m) => m.op === 'subscribed', 10000);
    expect(sub, '应收到 subscribed 消息').not.toBeNull();
    expect(sub!.pipelineId).toBeTruthy();
    ws.close();
});

test('WS-3 端到端 AC-10：订阅后源表 INSERT → 10s 内收到 INSERT 事件', async () => {
    test.skip(!pipeline || !key, '无 RUNNING 管道或 Key');
    const {ws, messages} = await openSession(key!.apiKey);
    ws.send(JSON.stringify({op: 'subscribe', pipelineId: pipeline!.id}));

    const sub = await waitMessage(messages, (m) => m.op === 'subscribed', 10000);
    expect(sub, '订阅前置失败').not.toBeNull();

    // 向源表写入触发 CDC
    const rowName = F4_DATA_PREFIX + Date.now();
    mysqlExec(
        `INSERT INTO testdb.${pipeline!.sourceTable} (name, amount) VALUES ('${rowName}', 999);`,
    );

    const start = Date.now();
    const ev = await waitMessage(
        messages,
        (m) => m.opType === 'INSERT' && m.table === pipeline!.sourceTable,
        10000,
    );
    const elapsed = Date.now() - start;
    expect(ev, '10s 内应收到 INSERT 事件').not.toBeNull();
    expect(elapsed).toBeLessThanOrEqual(10000);

    expect(ev!.opType).toBe('INSERT');
    expect(ev!.table).toBe(pipeline!.sourceTable);
    expect((ev!.data as WsMessage).name).toBe(rowName);
    expect(ev!.ts).toBeTruthy();
    ws.close();
});

test('WS-4 订阅校验：未绑定管道 → error 9005', async () => {
    test.skip(!key, '无 Key');
    const {ws, messages} = await openSession(key!.apiKey);
    ws.send(JSON.stringify({op: 'subscribe', pipelineId: '999999999999999999'}));
    const err = await waitMessage(messages, (m) => m.op === 'error', 10000);
    expect(err, '应收到 error 消息').not.toBeNull();
    expect(err!.code).toBe(9005);
    ws.close();
});
