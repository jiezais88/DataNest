import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 11 F3 执行队列 E2E 测试数据辅助：自播种自清理（用户确认 2026-08-15）。
 *
 * 范围：队列 CRUD（QU-1/5）+ 删除约束（QU-3）+ DAG 绑定（QU-2/4）+
 * 排队调度（QU-6）+ 审计（QU-7）+ 队列页 UI + 权限（仅超管）。
 *
 * 数据策略：
 * - 临时队列 e2e_s11f3_q（并发 2）/ e2e_s11f3_solo（并发 1，QU-6 排队用）
 * - 临时 DAG e2e_s11f3_dag（绑定 e2e_s11f3_solo，纯 SQL 节点 SELECT 1，快执行）
 * - 全部 e2e_s11f3_ 前缀，cleanup 物理清理（含既有残留）。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};

export const PREFIX = 'e2e_s11f3_';

/** 测试队列名（seed 创建） */
export const Q_CRUD = `${PREFIX}q`;
/** QU-6 排队专用队列（并发 1） */
export const Q_SOLO = `${PREFIX}solo`;
/** 绑定 DAG 名（QU-4/6 用） */
export const DAG_NAME = `${PREFIX}dag`;

// ==================== DB 直连辅助 ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

export const psqlSys = (sql: string): string => psql('datanest_system', sql);
export const psqlEng = (sql: string): string => psql('datanest_engineering', sql);

// ==================== 播种（beforeAll） ====================

/**
 * 播种：清理残留 → 建两个临时队列（Q_CRUD 并发 2 / Q_SOLO 并发 1）。
 * DAG 由用例自行创建（QU-4/6 需要不同队列绑定）。
 */
export async function seedF3(): Promise<void> {
    cleanupPhysical();
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);

    // 建 CRUD 队列（并发 2）
    await api.post('/engineering/execution-queues', {
        queueName: Q_CRUD, maxConcurrency: 2, description: 'Sprint11 F3 E2E CRUD 队列',
    });
    // 建排队专用队列（并发 1）
    await api.post('/engineering/execution-queues', {
        queueName: Q_SOLO, maxConcurrency: 1, description: 'Sprint11 F3 E2E 排队测试队列',
    });

    await api.dispose();
}

// ==================== 清理（afterAll） ====================

/** 物理清理全部 e2e_s11f3_ 前缀资源（队列 + 绑定 DAG + 执行历史），保证队列可删 */
export function cleanupPhysical(): void {
    // 1) 先删 DAG 绑定（队列删除前置：有 DAG 绑定拒绝删除）
    //    DAG 删除会级联删 execution/node_execution，且经 PowerJob 清理 workflow
    psqlEng(`
        DELETE FROM node_execution WHERE execution_id IN (
            SELECT id FROM dag_execution WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%'));
        DELETE FROM dag_edge WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%');
        DELETE FROM dag_node WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%');
        DELETE FROM dag_execution WHERE dag_id IN (SELECT id FROM dag WHERE name LIKE '${PREFIX}%');
        DELETE FROM dag WHERE name LIKE '${PREFIX}%';
        -- 2) 再删队列
        DELETE FROM execution_queue WHERE queue_name LIKE '${PREFIX}%';
    `);
}

export async function cleanupF3(): Promise<void> {
    try {
        cleanupPhysical();
    } catch (e) {
        console.warn('[f3 cleanup] 清理异常（可忽略，人工复查）:', e);
    }
}
