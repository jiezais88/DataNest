import {Api} from './api';
import {psql, scalar} from './db';
import {
    ADMIN,
    TEST_USERS,
    TPL_PREFIX,
    QUALITY_PREFIX,
    QUALITY_DS_NAME,
    QUALITY_DB,
    QUALITY_TABLE,
    QUALITY_SYNC_JOB,
} from './data';

/**
 * Sprint 6 规则模板库测试数据播种/清理。
 * 所有函数幂等：重复执行不会产生重复数据。
 *
 * 注意：
 * - 内置四类模板由 Flyway V3.6.0 迁移脚本插入，本模块不负责播种内置模板，
 *   仅清理/断言它们存在。
 * - 自定义测试模板统一使用 e2e_s6 前缀命名，便于播种与清理。
 */

const ERR = (e: unknown) => String(e).slice(0, 300);

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
              FROM sys_user
              WHERE username = '${u.username}'`);
    }
}

// ==================== 规则模板 ====================

/**
 * 播种规则模板测试数据（幂等）：
 * 先清掉历史 e2e_s6 前缀模板，再插入若干自定义模板。
 * 内置四类模板由迁移脚本保证存在，这里不动。
 */
export function seedTemplates(): void {
    psql(`DELETE
          FROM quality_rule_template
          WHERE name LIKE '${TPL_PREFIX}%'`);

    const insert = `
        INSERT INTO quality_rule_template
        (name, type, description, sql_template, result_metric, builtin, enabled, created_by, updated_by)
        VALUES ('${TPL_PREFIX}_完整性', 'COMPLETENESS', 'e2e s6 自定义完整性模板',
                'SELECT (COUNT(*) - COUNT({column})) * 1.0 / COUNT(*) AS null_rate FROM {table}',
                'null_rate', 0, 1, 0, 0),
               ('${TPL_PREFIX}_唯一性', 'UNIQUENESS', 'e2e s6 自定义唯一性模板',
                'SELECT COUNT(*) - COUNT(DISTINCT {column}) AS duplicate_count FROM {table}',
                'duplicate_count', 0, 1, 0, 0),
               ('${TPL_PREFIX}_停用模板', 'RANGE', 'e2e s6 自定义停用模板',
                'SELECT COUNT(*) AS total FROM {table}',
                'out_of_range_rate', 0, 0, 0, 0);
    `;
    psql(insert);
}

/** 清理规则模板测试数据（仅 e2e_s6 前缀，不动内置） */
export function cleanupTemplates(): void {
    psql(`DELETE
          FROM quality_rule_template
          WHERE name LIKE '${TPL_PREFIX}%'`);
}

// ==================== 质量任务 / 质量规则元数据 ====================

/** 测试元数据数据源固定 ID（避免 snowflake 冲突） */
const QUALITY_DS_ID = '9000010000000000001';
/** 测试元数据表固定 ID */
const QUALITY_TABLE_ID = '9000010000000000002';

/**
 * 播种质量任务/规则所需的独立测试元数据（幂等）：
 * - MYSQL 数据源（无 schema，选表流程为 数据源 → 库 → 表）
 * - metadata_table（source_status=ONLINE, source_type=EXTERNAL）
 * - metadata_column（id/order_no/amount 三个字段）
 * 全部带 QUALITY_PREFIX 前缀，不影响现有环境。
 */
export function seedQualityMetadata(): void {
    // 清理历史测试数据（先清外键引用，再清主表）
    psql(`DELETE FROM metadata_column WHERE table_id = ${QUALITY_TABLE_ID}`);
    psql(`DELETE FROM metadata_table WHERE id = ${QUALITY_TABLE_ID}`);
    psql(`DELETE FROM datasource_connection WHERE id = ${QUALITY_DS_ID}`);

    const ds = scalar(`SELECT id FROM datasource_connection WHERE id = ${QUALITY_DS_ID}`);
    if (!ds) {
        psql(`
            INSERT INTO datasource_connection
            (id, name, type, host, port, database_name, schema_name, username, encrypted_password,
             status, created_at, updated_at, auto_collect_on_save)
            VALUES (${QUALITY_DS_ID}, '${QUALITY_DS_NAME}', 'MYSQL', 'middleware-test-mysql', 3306,
                    '${QUALITY_DB}', NULL, 'testuser', 'x', 'NORMAL', now(), now(), 0)
        `);
    }

    const tbl = scalar(`SELECT id FROM metadata_table WHERE id = ${QUALITY_TABLE_ID}`);
    if (!tbl) {
        psql(`
            INSERT INTO metadata_table
            (id, datasource_id, database_name, schema_name, table_name, table_comment,
             source_status, source_type, created_at, updated_at)
            VALUES (${QUALITY_TABLE_ID}, ${QUALITY_DS_ID}, '${QUALITY_DB}', NULL, '${QUALITY_TABLE}',
                    'e2e s6 质量测试表', 'ONLINE', 'EXTERNAL', now(), now())
        `);
    }

    const cols = scalar(`SELECT count(*) FROM metadata_column WHERE table_id = ${QUALITY_TABLE_ID}`);
    if (cols === '0') {
        psql(`
            INSERT INTO metadata_column
            (id, table_id, column_name, data_type, column_comment, nullable, ordinal_position,
             source_type, source_status, created_at, updated_at)
            VALUES
            (9000010000000000101, ${QUALITY_TABLE_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
            (9000010000000000102, ${QUALITY_TABLE_ID}, 'order_no', 'varchar', '订单号', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
            (9000010000000000103, ${QUALITY_TABLE_ID}, 'amount', 'decimal', '金额', true, 3, 'EXTERNAL', 'ONLINE', now(), now())
        `);
    }
}

/** 播种自动触发绑定用同步任务（幂等） */
export function seedSyncJob(): void {
    psql(`DELETE FROM sync_job WHERE name = '${QUALITY_SYNC_JOB}'`);
    psql(`
        INSERT INTO sync_job
        (id, name, source_datasource_id, source_database, source_tables, source_tables_detail,
         sync_mode, trigger_type, retry_times, retry_interval, field_mapping, status,
         schedule_enabled, execution_status, created_at, updated_at,
         read_rate_limit_mbps, write_rate_limit_rows_per_second, rate_limit_enabled)
        VALUES (9000010000000000003, '${QUALITY_SYNC_JOB}', ${QUALITY_DS_ID}, '${QUALITY_DB}',
                '["${QUALITY_TABLE}"]', '[]', 'FULL', 'MANUAL', 0, 0, '[]', 'NORMAL',
                0, 'IDLE', now(), now(), 0, 0, 0)
    `);
}

/** 清理质量任务/规则测试数据（含元数据、同步任务，幂等） */
export function cleanupQualityData(): void {
    // 先清理质量规则/任务（避免外键残留）
    psql(`DELETE FROM quality_rule WHERE job_id IN (SELECT id FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%')`);
    psql(`DELETE FROM quality_job WHERE name LIKE '${QUALITY_PREFIX}%'`);
    // 清理元数据
    psql(`DELETE FROM metadata_column WHERE table_id = ${QUALITY_TABLE_ID}`);
    psql(`DELETE FROM metadata_table WHERE id = ${QUALITY_TABLE_ID}`);
    psql(`DELETE FROM datasource_connection WHERE id = ${QUALITY_DS_ID}`);
    // 清理同步任务
    psql(`DELETE FROM sync_job WHERE name = '${QUALITY_SYNC_JOB}'`);
}

// ==================== 清理 / 播种 ====================

/** 清理全部 Sprint 6 测试数据（幂等） */
export async function cleanupAll(): Promise<void> {
    try {
        deleteTestUsers();
    } catch (e) {
        console.warn('sprint6 cleanup users:', ERR(e));
    }
    try {
        cleanupTemplates();
    } catch (e) {
        console.warn('sprint6 cleanup templates:', ERR(e));
    }
    try {
        cleanupQualityData();
    } catch (e) {
        console.warn('sprint6 cleanup quality:', ERR(e));
    }
}

/** 全量播种（globalSetup 调用，幂等） */
export async function seedAll(): Promise<void> {
    await ensureTestUsers();
    seedTemplates();
    seedQualityMetadata();
    seedSyncJob();
}
