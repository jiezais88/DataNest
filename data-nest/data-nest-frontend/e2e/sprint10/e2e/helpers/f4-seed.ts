import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 10 F4 E2E 测试数据辅助：WebSocket 实时订阅端到端（真实 CDC 链路）。
 *
 * 链路：MySQL binlog → Flink CDC 事件作业 → Kafka topic `cdc-events-{pipelineId}` → data-service KafkaEventConsumer → WebSocket fan-out。
 * 前置：需一个 RUNNING 的 CDC 管道（`e2e_s10_f4_` 前缀，含事件作业）+ 可写源表（test-mysql 容器）。
 * - 订阅方是业务端（Node.js `ws` 库），握手带 X-API-Key 头（浏览器原生 WebSocket 无法自定义头）。
 * - 管道 ID / Key ID 为 19 位 Long，全程字符串持有避免 Number 精度丢失。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};
export const F4_PREFIX = 'e2e_s10_f4_';
/** 源表测试数据命名前缀（写入 + 清理统一） */
export const F4_DATA_PREFIX = 'e2e-s10-f4-';
/** WebSocket 订阅地址（经网关） */
export const WS_URL = 'ws://localhost:8080/api/data-service/ws/events';

/** 可订阅管道（源表信息，用于写入源表触发 CDC） */
export interface F4Pipeline {
    id: string;
    name: string;
    sourceDatabase: string;
    sourceTable: string;
    primaryKey: string;
}

/** 管道分页项（page 端点 records 元素） */
interface PipelinePageItem {
    id: string;
    name: string;
    status: string;
}

/** 管道详情（detail 端点 data） */
interface PipelineDetail {
    id: string;
    name: string;
    sourceDatabase: string;
    tables?: Array<{sourceTable: string; primaryKey?: string}>;
}

/** PageResult<T> 信封 data（Long total 序列化为 string） */
interface PageData<T> {
    records?: T[];
    total?: string | number;
}

/** Key 创建响应（ApiKeyCreateResult） */
interface ApiKeyCreateResult {
    id: string;
    apiKey: string;
}

/** MySQL 源表直写（test-mysql 容器，docker exec -i 传 stdin，Windows 引号安全） */
export function mysqlExec(sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-test-mysql mysql -uroot -proot123`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

/** 查找 RUNNING 且可订阅（`e2e_s10_f4_` 前缀）的管道；无则返回 null */
export async function findRunningPipeline(): Promise<F4Pipeline | null> {
    const api = await Api.create();
    try {
        await api.login(ADMIN.username, ADMIN.password);
        const page = await api.get<PageData<PipelinePageItem>>(
            '/realtime/cdc/pipelines/page?page=1&pageSize=100',
        );
        const p = (page.records ?? []).find(
            (r) => r.status === 'RUNNING' && r.name.startsWith(F4_PREFIX),
        );
        if (!p) return null;
        const detail = await api.get<PipelineDetail>(`/realtime/cdc/pipelines/${p.id}`);
        const firstTable = detail.tables?.[0];
        return {
            id: detail.id,
            name: detail.name,
            sourceDatabase: detail.sourceDatabase,
            sourceTable: firstTable?.sourceTable ?? '',
            primaryKey: firstTable?.primaryKey ?? 'id',
        };
    } finally {
        await api.dispose();
    }
}

/** 创建 Key 绑定管道（pipelineIds 字符串数组，后端 Jackson 转 List<Long>），返回 {id, apiKey明文} */
export async function createKey(pipelineIds: string[]): Promise<{id: string; apiKey: string}> {
    const api = await Api.create();
    try {
        await api.login(ADMIN.username, ADMIN.password);
        const result = await api.post<ApiKeyCreateResult>('/data-service/api-keys', {
            name: F4_PREFIX + 'key_' + Date.now(),
            qpsLimit: 100,
            apiIds: [],
            pipelineIds,
        });
        return {id: result.id, apiKey: result.apiKey};
    } finally {
        await api.dispose();
    }
}

/** 物理删除 Key（后端级联清理 api_key_pipeline 绑定） */
export async function deleteKey(id: string): Promise<void> {
    const api = await Api.create();
    try {
        await api.login(ADMIN.username, ADMIN.password);
        await api.del(`/data-service/api-keys/${id}`);
    } finally {
        await api.dispose();
    }
}

/** 清理源表 F4 测试数据 */
export function cleanupF4SourceData(table: string): void {
    mysqlExec(`DELETE FROM testdb.${table} WHERE name LIKE '${F4_DATA_PREFIX}%';`);
}
