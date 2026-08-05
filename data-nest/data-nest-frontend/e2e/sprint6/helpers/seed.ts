import {Api} from './api';
import {psql, scalar} from './db';
import {mysqlExec, pgExec, quiet} from './exec-db';
import {encryptDataSourcePassword} from './encrypt';
import {
    ADMIN,
    TEST_USERS,
    TPL_PREFIX,
    QUALITY_PREFIX,
    QUALITY_DS_NAME,
    QUALITY_DB,
    QUALITY_TABLE,
    QUALITY_SYNC_JOB,
    EXEC_TABLE,
    EXEC_BAD_TABLE,
    EXEC_DS_MYSQL_NAME,
    EXEC_DS_PG_NAME,
    EXEC_MYSQL,
    EXEC_PG,
    ALERT_PREFIX,
    ALERT_JOB_ID,
    ALERT_JOB_SEVERE_ONLY_ID,
    ALERT_JOB_UNAVAILABLE_ID,
    ALERT_JOB_PASS_ID,
    ALERT_RULE_SEVERE_ID,
    ALERT_RULE_WARNING_ID,
    ALERT_RULE_SO_SEVERE_ID,
    ALERT_RULE_SO_WARNING_ID,
    ALERT_RULE_UNAVAILABLE_ID,
    ALERT_RULE_PASS_ID,
    SCORE_PREFIX,
    SCORE_TABLE_PASS_ID,
    SCORE_TABLE_WARN_ID,
    SCORE_TABLE_SEVERE_ID,
    SCORE_TABLE_UNAVAIL_ID,
    SCORE_TABLE_PASS,
    SCORE_TABLE_WARN,
    SCORE_TABLE_SEVERE,
    SCORE_TABLE_UNAVAIL,
    SCORE_RULE_PASS_1,
    SCORE_RULE_PASS_2,
    SCORE_RULE_WARN_1,
    SCORE_RULE_WARN_PASS,
    SCORE_RULE_SEVERE_1,
    SCORE_RULE_SEVERE_PASS,
    SCORE_RULE_UNAVAIL,
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

// ==================== 质量检查执行层（Sprint 8） ====================

/** 执行数据源固定 ID（避免 snowflake 冲突） */
const EXEC_DS_MYSQL_ID = '9000020000000000001';
const EXEC_DS_PG_ID = '9000020000000000002';
/** 执行目标表固定 ID（各数据源：成功表 + 失败表） */
const EXEC_TABLE_MYSQL_OK_ID = '9000020000000000011';
const EXEC_TABLE_MYSQL_BAD_ID = '9000020000000000012';
const EXEC_TABLE_PG_OK_ID = '9000020000000000021';
const EXEC_TABLE_PG_BAD_ID = '9000020000000000022';

/**
 * 播种执行层目标表：在 middleware-test-mysql / middleware-test-postgres 各建一张
 * e2e_s6_orders（带 id/order_no/amount 三列 + 若干行），供成功规则执行。幂等。
 */
export function seedExecTables(): void {
    // MYSQL
    quiet(mysqlExec, `
        CREATE TABLE IF NOT EXISTS ${EXEC_TABLE} (
            id BIGINT PRIMARY KEY,
            order_no VARCHAR(64),
            amount DECIMAL(18,2)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
    mysqlExec(`DELETE FROM ${EXEC_TABLE}`);
    mysqlExec(`
        INSERT INTO ${EXEC_TABLE} (id, order_no, amount) VALUES
        (1, 'A001', 100.00), (2, 'A002', 200.50), (3, NULL, 50.00), (4, 'A003', NULL);
    `);

    // PG（schema public，元数据表 schema_name=public，执行 SQL 拼 public.表名）
    quiet(pgExec, `
        CREATE TABLE IF NOT EXISTS ${EXEC_TABLE} (
            id BIGINT PRIMARY KEY,
            order_no VARCHAR(64),
            amount NUMERIC(18,2)
        );
    `);
    pgExec(`DELETE FROM ${EXEC_TABLE}`);
    pgExec(`
        INSERT INTO ${EXEC_TABLE} (id, order_no, amount) VALUES
        (1, 'P001', 10.00), (2, 'P002', 20.50), (3, NULL, 5.00), (4, 'P003', NULL);
    `);
}

/**
 * 播种执行层元数据（幂等）：
 * - 2 个执行数据源：e2e_s6_exec_ds（MYSQL，无 schema）+ e2e_s6_exec_pg_ds（POSTGRESQL，schema=public）
 *   密码用 AES-256-GCM 加密（密钥 DataNestDefaultEncryptionKey2026），确保 GenericSqlExecutor 可解密执行。
 * - 4 张 metadata_table：每数据源各 1 张成功表（e2e_s6_orders）+ 1 张失败表（e2e_s6_no_such_table，源库不存在）。
 * - 成功表带 id/order_no/amount 三列 metadata_column。
 */
export function seedExecMetadata(): void {
    // 清理历史（先清外键引用，再清主表）
    for (const id of [EXEC_TABLE_MYSQL_OK_ID, EXEC_TABLE_MYSQL_BAD_ID, EXEC_TABLE_PG_OK_ID, EXEC_TABLE_PG_BAD_ID]) {
        psql(`DELETE FROM metadata_column WHERE table_id = ${id}`);
        psql(`DELETE FROM metadata_table WHERE id = ${id}`);
    }
    psql(`DELETE FROM datasource_connection WHERE id IN (${EXEC_DS_MYSQL_ID}, ${EXEC_DS_PG_ID})`);

    const mysqlPass = encryptDataSourcePassword(EXEC_MYSQL.pass);
    const pgPass = encryptDataSourcePassword(EXEC_PG.pass);

    // 数据源
    psql(`
        INSERT INTO datasource_connection
        (id, name, type, host, port, database_name, schema_name, username, encrypted_password,
         status, created_at, updated_at, auto_collect_on_save)
        VALUES
        (${EXEC_DS_MYSQL_ID}, '${EXEC_DS_MYSQL_NAME}', 'MYSQL', '${EXEC_MYSQL.host}', ${EXEC_MYSQL.port},
         '${EXEC_MYSQL.db}', NULL, '${EXEC_MYSQL.user}', '${mysqlPass}', 'NORMAL', now(), now(), 0),
        (${EXEC_DS_PG_ID}, '${EXEC_DS_PG_NAME}', 'POSTGRESQL', '${EXEC_PG.host}', ${EXEC_PG.port},
         '${EXEC_PG.db}', '${EXEC_PG.schema}', '${EXEC_PG.user}', '${pgPass}', 'NORMAL', now(), now(), 0);
    `);

    // 元数据表
    psql(`
        INSERT INTO metadata_table
        (id, datasource_id, database_name, schema_name, table_name, table_comment,
         source_status, source_type, created_at, updated_at)
        VALUES
        (${EXEC_TABLE_MYSQL_OK_ID}, ${EXEC_DS_MYSQL_ID}, '${EXEC_MYSQL.db}', NULL, '${EXEC_TABLE}',
         'e2e s6 mysql 成功表', 'ONLINE', 'EXTERNAL', now(), now()),
        (${EXEC_TABLE_MYSQL_BAD_ID}, ${EXEC_DS_MYSQL_ID}, '${EXEC_MYSQL.db}', NULL, '${EXEC_BAD_TABLE}',
         'e2e s6 mysql 失败表(不存在)', 'ONLINE', 'EXTERNAL', now(), now()),
        (${EXEC_TABLE_PG_OK_ID}, ${EXEC_DS_PG_ID}, '${EXEC_PG.db}', '${EXEC_PG.schema}', '${EXEC_TABLE}',
         'e2e s6 pg 成功表', 'ONLINE', 'EXTERNAL', now(), now()),
        (${EXEC_TABLE_PG_BAD_ID}, ${EXEC_DS_PG_ID}, '${EXEC_PG.db}', '${EXEC_PG.schema}', '${EXEC_BAD_TABLE}',
         'e2e s6 pg 失败表(不存在)', 'ONLINE', 'EXTERNAL', now(), now());
    `);

    // 成功表字段（供选字段类规则 / 明细表名展示）
    psql(`
        INSERT INTO metadata_column
        (id, table_id, column_name, data_type, column_comment, nullable, ordinal_position,
         source_type, source_status, created_at, updated_at)
        VALUES
        (9000020000000000111, ${EXEC_TABLE_MYSQL_OK_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000020000000000112, ${EXEC_TABLE_MYSQL_OK_ID}, 'order_no', 'varchar', '订单号', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000020000000000113, ${EXEC_TABLE_MYSQL_OK_ID}, 'amount', 'decimal', '金额', true, 3, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000020000000000211, ${EXEC_TABLE_PG_OK_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000020000000000212, ${EXEC_TABLE_PG_OK_ID}, 'order_no', 'varchar', '订单号', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000020000000000213, ${EXEC_TABLE_PG_OK_ID}, 'amount', 'numeric', '金额', true, 3, 'EXTERNAL', 'ONLINE', now(), now());
    `);
}

/** 清理执行层目标表数据（保留表结构；幂等） */
export function cleanupExecTables(): void {
    quiet(mysqlExec, `DROP TABLE IF EXISTS ${EXEC_TABLE}`);
    quiet(pgExec, `DROP TABLE IF EXISTS ${EXEC_TABLE}`);
}

/** 清理执行层元数据（数据源 / 元数据表 / 字段，幂等） */
export function cleanupExecMetadata(): void {
    for (const id of [EXEC_TABLE_MYSQL_OK_ID, EXEC_TABLE_MYSQL_BAD_ID, EXEC_TABLE_PG_OK_ID, EXEC_TABLE_PG_BAD_ID]) {
        psql(`DELETE FROM metadata_column WHERE table_id = ${id}`);
        psql(`DELETE FROM metadata_table WHERE id = ${id}`);
    }
    psql(`DELETE FROM datasource_connection WHERE id IN (${EXEC_DS_MYSQL_ID}, ${EXEC_DS_PG_ID})`);
}

// ==================== 分级邮件告警（Sprint 6） ====================

/** 执行数据源固定 ID（与 seedExecMetadata 一致，供规则 SQL 复用） */
const ALERT_EXEC_DS_MYSQL_ID = '9000020000000000001';
/** 执行目标表固定 ID（MYSQL 成功表，e2e_s6_orders，COUNT(*)=4） */
const ALERT_EXEC_TABLE_MYSQL_OK_ID = '9000020000000000011';

/** 计数 SQL 公共前缀（e2e_s6_orders 共 4 行 → value=4） */
const COUNT_SQL = (table: string) => `SELECT COUNT(*) AS total FROM ${table}`;

/**
 * 播种分级告警测试元数据（幂等）：
 * 在 e2e_s6_orders（COUNT(*)=4）上用 CUSTOM_SQL 规则 + warning/severe 阈值产出确定分级：
 * - 主链路任务 ALERT_JOB_ID（SEVERE_WARNING）：
 *   - ALERT_RULE_SEVERE_ID：warning=2/severe=3 → value 4 → SEVERE
 *   - ALERT_RULE_WARNING_ID：warning=3/severe=5 → value 4 → WARNING
 * - SEVERE_ONLY 任务 ALERT_JOB_SEVERE_ONLY_ID（仅严重，验证排除警告）：
 *   - ALERT_RULE_SO_SEVERE_ID：warning=2/severe=3 → SEVERE
 *   - ALERT_RULE_SO_WARNING_ID：warning=3/severe=5 → WARNING（应被排除不告警）
 * - UNAVAILABLE 任务 ALERT_JOB_UNAVAILABLE_ID：ALERT_RULE_UNAVAILABLE_ID 查不存在表 → SQL 失败 → UNAVAILABLE（不告警）
 * - PASS 任务 ALERT_JOB_PASS_ID：ALERT_RULE_PASS_ID 无阈值 → PASS（不告警）
 *
 * 数据源/表沿用执行层 seedExecMetadata 播种的 MYSQL 执行数据源与 e2e_s6_orders，
 * 本函数只建质量任务 + 质量规则，不重建数据源/表。
 */
export function seedQualityAlerts(): void {
    // 清理历史（先规则后任务）
    psql(`DELETE FROM quality_rule WHERE id IN (
        ${ALERT_RULE_SEVERE_ID}, ${ALERT_RULE_WARNING_ID},
        ${ALERT_RULE_SO_SEVERE_ID}, ${ALERT_RULE_SO_WARNING_ID},
        ${ALERT_RULE_UNAVAILABLE_ID}, ${ALERT_RULE_PASS_ID})`);
    psql(`DELETE FROM quality_job WHERE id IN (
        ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID},
        ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);

    // 质量任务（4 个）
    psql(`
        INSERT INTO quality_job
        (id, name, description, datasource_id, enabled, scheduled_enabled, auto_trigger_enabled,
         alert_level, created_at, updated_at)
        VALUES
        (${ALERT_JOB_ID}, '${ALERT_PREFIX}_main', 'e2e s6 分级主链路(严重+警告)', ${ALERT_EXEC_DS_MYSQL_ID}, 1, 0, 0,
         'SEVERE_WARNING', now(), now()),
        (${ALERT_JOB_SEVERE_ONLY_ID}, '${ALERT_PREFIX}_severe_only', 'e2e s6 仅严重(排除警告)', ${ALERT_EXEC_DS_MYSQL_ID}, 1, 0, 0,
         'SEVERE_ONLY', now(), now()),
        (${ALERT_JOB_UNAVAILABLE_ID}, '${ALERT_PREFIX}_unavailable', 'e2e s6 不可用(SQL失败不告警)', ${ALERT_EXEC_DS_MYSQL_ID}, 1, 0, 0,
         'SEVERE_WARNING', now(), now()),
        (${ALERT_JOB_PASS_ID}, '${ALERT_PREFIX}_pass', 'e2e s6 通过(不告警)', ${ALERT_EXEC_DS_MYSQL_ID}, 1, 0, 0,
         'SEVERE_WARNING', now(), now());
    `);

    // 质量规则（带阈值，CUSTOM_SQL，引用 MYSQL 执行数据源的表）
    psql(`
        INSERT INTO quality_rule
        (id, name, type, table_id, sql_expression, result_metric, warning_threshold, severe_threshold,
         weight, enabled, created_at, updated_at)
        VALUES
        (${ALERT_RULE_SEVERE_ID}, '${ALERT_PREFIX}_severe_rule', 'CUSTOM_SQL', ${ALERT_EXEC_TABLE_MYSQL_OK_ID},
         '${COUNT_SQL('e2e_s6_orders')}', 'total', 2, 3, 1, 1, now(), now()),
        (${ALERT_RULE_WARNING_ID}, '${ALERT_PREFIX}_warning_rule', 'CUSTOM_SQL', ${ALERT_EXEC_TABLE_MYSQL_OK_ID},
         '${COUNT_SQL('e2e_s6_orders')}', 'total', 3, 5, 1, 1, now(), now()),
        (${ALERT_RULE_SO_SEVERE_ID}, '${ALERT_PREFIX}_so_severe_rule', 'CUSTOM_SQL', ${ALERT_EXEC_TABLE_MYSQL_OK_ID},
         '${COUNT_SQL('e2e_s6_orders')}', 'total', 2, 3, 1, 1, now(), now()),
        (${ALERT_RULE_SO_WARNING_ID}, '${ALERT_PREFIX}_so_warning_rule', 'CUSTOM_SQL', ${ALERT_EXEC_TABLE_MYSQL_OK_ID},
         '${COUNT_SQL('e2e_s6_orders')}', 'total', 3, 5, 1, 1, now(), now()),
        (${ALERT_RULE_UNAVAILABLE_ID}, '${ALERT_PREFIX}_unavailable_rule', 'CUSTOM_SQL', ${ALERT_EXEC_TABLE_MYSQL_OK_ID},
         '${COUNT_SQL('e2e_s6_no_such_table')}', 'total', 2, 3, 1, 1, now(), now()),
        (${ALERT_RULE_PASS_ID}, '${ALERT_PREFIX}_pass_rule', 'CUSTOM_SQL', ${ALERT_EXEC_TABLE_MYSQL_OK_ID},
         '${COUNT_SQL('e2e_s6_orders')}', 'total', 5, 6, 1, 1, now(), now());
    `);

    // 任务引用规则（Sprint 7 关联表 quality_job_rule），显式配对
    psql(`
        INSERT INTO quality_job_rule (job_id, rule_id) VALUES
        (${ALERT_JOB_ID}, ${ALERT_RULE_SEVERE_ID}),
        (${ALERT_JOB_ID}, ${ALERT_RULE_WARNING_ID}),
        (${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_RULE_SO_SEVERE_ID}),
        (${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_RULE_SO_WARNING_ID}),
        (${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_RULE_UNAVAILABLE_ID}),
        (${ALERT_JOB_PASS_ID}, ${ALERT_RULE_PASS_ID})
        ON CONFLICT (job_id, rule_id) DO NOTHING;
    `);
}

/** 清理分级告警测试数据（质量规则/任务/批次/告警历史，幂等） */
export function cleanupQualityAlerts(): void {
    // 先清批次明细/批次（按任务 id）
    psql(`DELETE FROM quality_check_detail WHERE batch_id IN (
        SELECT id FROM quality_check_batch WHERE job_id IN (
            ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID}))`);
    psql(`DELETE FROM quality_check_batch WHERE job_id IN (
        ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
    // 告警历史（QUALITY 对象）
    psql(`DELETE FROM alert_history WHERE object_type = 'QUALITY' AND object_id IN (
        ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
    // 规则
    psql(`DELETE FROM quality_rule WHERE id IN (
        ${ALERT_RULE_SEVERE_ID}, ${ALERT_RULE_WARNING_ID},
        ${ALERT_RULE_SO_SEVERE_ID}, ${ALERT_RULE_SO_WARNING_ID},
        ${ALERT_RULE_UNAVAILABLE_ID}, ${ALERT_RULE_PASS_ID})`);
    // 任务
    psql(`DELETE FROM quality_job WHERE id IN (
        ${ALERT_JOB_ID}, ${ALERT_JOB_SEVERE_ONLY_ID}, ${ALERT_JOB_UNAVAILABLE_ID}, ${ALERT_JOB_PASS_ID})`);
}

// ==================== 表级质量评分（Sprint 6 NG8） ====================

/** 评分用执行数据源固定 ID（复用执行层 MYSQL 数据源，供规则 SQL 复用） */
const SCORE_EXEC_DS_MYSQL_ID = '9000020000000000001';
/** 计数 SQL 公共前缀 */
const SCORE_COUNT_SQL = (table: string) => `SELECT COUNT(*) AS total FROM ${table}`;
/** 空表/无结果查询 SQL（U 表查不存在表 → SQL 失败 → UNAVAILABLE） */
const SCORE_BAD_SQL = `SELECT COUNT(*) AS total FROM ${SCORE_PREFIX}_no_such_table`;

/**
 * 播种表级质量评分测试元数据（幂等）：
 * 在 MYSQL 执行数据源（middleware-test-mysql）建 4 张评分物理表，每张表行数不同以控制 COUNT 值；
 * 再建 4 条 metadata_table（不同物理表名满足唯一约束）+ 7 条启用质量规则。
 *
 * 多档评分场景（默认扣分配置 warningDeduct=10 / severeDeduct=30 / badThreshold=60）：
 * - P 全通过表 SCORE_TABLE_PASS（COUNT=2）：R1/R2 阈值 3/4 → 均 PASS
 *     基础分=100×2/2=100，扣分 0 → 100.00 EXCELLENT，pass=2
 * - W 警告表 SCORE_TABLE_WARN（COUNT=4）：R3 阈值 3/5 → WARNING，R4 阈值 5/6 → PASS(weight=4)
 *     基础分=100×4/(1+4)=80，警告扣 1×10=10 → 70.00 WARNING，pass=1/warning=1
 * - B 严重表 SCORE_TABLE_SEVERE（COUNT=4）：R5 阈值 2/3 → SEVERE，R6 阈值 5/6 → PASS
 *     严重强制 BAD，基础分=100×1/2=50，严重扣 1×30=30 → min(20, 59.99)=20.00 BAD，pass=1/severe=1
 * - U 不可用表 SCORE_TABLE_UNAVAIL：R7 查不存在表 → UNAVAILABLE 不参与 → 无有效规则 → 不落评分行
 *
 * 数据源沿用执行层 seedExecMetadata 播种的 MYSQL 执行数据源，本函数只建评分物理表 + 元数据 + 规则。
 */
export function seedQualityScores(): void {
    // 清理历史（先清评分/明细/规则，再清表元数据，最后 DROP 物理表）
    psql(`DELETE FROM quality_score WHERE table_id IN (
        ${SCORE_TABLE_PASS_ID}, ${SCORE_TABLE_WARN_ID}, ${SCORE_TABLE_SEVERE_ID}, ${SCORE_TABLE_UNAVAIL_ID})`);
    psql(`DELETE FROM quality_check_detail WHERE rule_id IN (
        ${SCORE_RULE_PASS_1}, ${SCORE_RULE_PASS_2}, ${SCORE_RULE_WARN_1}, ${SCORE_RULE_WARN_PASS},
        ${SCORE_RULE_SEVERE_1}, ${SCORE_RULE_SEVERE_PASS}, ${SCORE_RULE_UNAVAIL})`);
    psql(`DELETE FROM quality_check_batch WHERE id IN (
        SELECT DISTINCT batch_id FROM quality_check_detail WHERE rule_id IN (
            ${SCORE_RULE_PASS_1}, ${SCORE_RULE_PASS_2}, ${SCORE_RULE_WARN_1}, ${SCORE_RULE_WARN_PASS},
            ${SCORE_RULE_SEVERE_1}, ${SCORE_RULE_SEVERE_PASS}, ${SCORE_RULE_UNAVAIL}))`);
    psql(`DELETE FROM quality_rule WHERE id IN (
        ${SCORE_RULE_PASS_1}, ${SCORE_RULE_PASS_2}, ${SCORE_RULE_WARN_1}, ${SCORE_RULE_WARN_PASS},
        ${SCORE_RULE_SEVERE_1}, ${SCORE_RULE_SEVERE_PASS}, ${SCORE_RULE_UNAVAIL})`);
    for (const id of [SCORE_TABLE_PASS_ID, SCORE_TABLE_WARN_ID, SCORE_TABLE_SEVERE_ID, SCORE_TABLE_UNAVAIL_ID]) {
        psql(`DELETE FROM metadata_column WHERE table_id = ${id}`);
        psql(`DELETE FROM metadata_table WHERE id = ${id}`);
    }

    // 建评分物理表（不同表名 + 行数控制 COUNT 值）
    quiet(mysqlExec, `
        CREATE TABLE IF NOT EXISTS ${SCORE_TABLE_PASS} (
            id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount DECIMAL(18,2)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
    mysqlExec(`DELETE FROM ${SCORE_TABLE_PASS}`);
    mysqlExec(`INSERT INTO ${SCORE_TABLE_PASS} (id, order_no, amount) VALUES (1,'P1',1.00),(2,'P2',2.00);`);

    quiet(mysqlExec, `
        CREATE TABLE IF NOT EXISTS ${SCORE_TABLE_WARN} (
            id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount DECIMAL(18,2)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
    mysqlExec(`DELETE FROM ${SCORE_TABLE_WARN}`);
    mysqlExec(`INSERT INTO ${SCORE_TABLE_WARN} (id, order_no, amount) VALUES (1,'W1',1.00),(2,'W2',2.00),(3,'W3',3.00),(4,'W4',4.00);`);

    quiet(mysqlExec, `
        CREATE TABLE IF NOT EXISTS ${SCORE_TABLE_SEVERE} (
            id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount DECIMAL(18,2)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
    mysqlExec(`DELETE FROM ${SCORE_TABLE_SEVERE}`);
    mysqlExec(`INSERT INTO ${SCORE_TABLE_SEVERE} (id, order_no, amount) VALUES (1,'B1',1.00),(2,'B2',2.00),(3,'B3',3.00),(4,'B4',4.00);`);

    quiet(mysqlExec, `
        CREATE TABLE IF NOT EXISTS ${SCORE_TABLE_UNAVAIL} (
            id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount DECIMAL(18,2)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    `);
    mysqlExec(`DELETE FROM ${SCORE_TABLE_UNAVAIL}`);
    mysqlExec(`INSERT INTO ${SCORE_TABLE_UNAVAIL} (id, order_no, amount) VALUES (1,'U1',1.00),(2,'U2',2.00),(3,'U3',3.00),(4,'U4',4.00);`);

    // 元数据表（同一 MYSQL 数据源 + testdb，无 schema，不同表名）
    psql(`
        INSERT INTO metadata_table
        (id, datasource_id, database_name, schema_name, table_name, table_comment,
         source_status, source_type, created_at, updated_at)
        VALUES
        (${SCORE_TABLE_PASS_ID}, ${SCORE_EXEC_DS_MYSQL_ID}, 'testdb', NULL, '${SCORE_TABLE_PASS}',
         'e2e s6 评分全通过表', 'ONLINE', 'EXTERNAL', now(), now()),
        (${SCORE_TABLE_WARN_ID}, ${SCORE_EXEC_DS_MYSQL_ID}, 'testdb', NULL, '${SCORE_TABLE_WARN}',
         'e2e s6 评分警告表', 'ONLINE', 'EXTERNAL', now(), now()),
        (${SCORE_TABLE_SEVERE_ID}, ${SCORE_EXEC_DS_MYSQL_ID}, 'testdb', NULL, '${SCORE_TABLE_SEVERE}',
         'e2e s6 评分严重表', 'ONLINE', 'EXTERNAL', now(), now()),
        (${SCORE_TABLE_UNAVAIL_ID}, ${SCORE_EXEC_DS_MYSQL_ID}, 'testdb', NULL, '${SCORE_TABLE_UNAVAIL}',
         'e2e s6 评分不可用表', 'ONLINE', 'EXTERNAL', now(), now());
    `);

    // 质量规则（CUSTOM_SQL，COUNT 值由阈值控制分级，weight 控制分值）
    psql(`
        INSERT INTO quality_rule
        (id, name, type, table_id, sql_expression, result_metric, warning_threshold, severe_threshold,
         weight, enabled, created_at, updated_at)
        VALUES
        (${SCORE_RULE_PASS_1}, '${SCORE_PREFIX}_pass_r1', 'CUSTOM_SQL', ${SCORE_TABLE_PASS_ID},
         '${SCORE_COUNT_SQL(SCORE_TABLE_PASS)}', 'total', 3, 4, 1, 1, now(), now()),
        (${SCORE_RULE_PASS_2}, '${SCORE_PREFIX}_pass_r2', 'CUSTOM_SQL', ${SCORE_TABLE_PASS_ID},
         '${SCORE_COUNT_SQL(SCORE_TABLE_PASS)}', 'total', 3, 4, 1, 1, now(), now()),
        (${SCORE_RULE_WARN_1}, '${SCORE_PREFIX}_warn_r1', 'CUSTOM_SQL', ${SCORE_TABLE_WARN_ID},
         '${SCORE_COUNT_SQL(SCORE_TABLE_WARN)}', 'total', 3, 5, 1, 1, now(), now()),
        (${SCORE_RULE_WARN_PASS}, '${SCORE_PREFIX}_warn_r2', 'CUSTOM_SQL', ${SCORE_TABLE_WARN_ID},
         '${SCORE_COUNT_SQL(SCORE_TABLE_WARN)}', 'total', 5, 6, 4, 1, now(), now()),
        (${SCORE_RULE_SEVERE_1}, '${SCORE_PREFIX}_severe_r1', 'CUSTOM_SQL', ${SCORE_TABLE_SEVERE_ID},
         '${SCORE_COUNT_SQL(SCORE_TABLE_SEVERE)}', 'total', 2, 3, 1, 1, now(), now()),
        (${SCORE_RULE_SEVERE_PASS}, '${SCORE_PREFIX}_severe_r2', 'CUSTOM_SQL', ${SCORE_TABLE_SEVERE_ID},
         '${SCORE_COUNT_SQL(SCORE_TABLE_SEVERE)}', 'total', 5, 6, 1, 1, now(), now()),
        (${SCORE_RULE_UNAVAIL}, '${SCORE_PREFIX}_unavail_r1', 'CUSTOM_SQL', ${SCORE_TABLE_UNAVAIL_ID},
         '${SCORE_BAD_SQL}', 'total', 3, 5, 1, 1, now(), now());
    `);
}

/** 清理表级质量评分测试数据（评分/明细/批次/规则/元数据/物理表，幂等） */
export function cleanupQualityScores(): void {
    psql(`DELETE FROM quality_score WHERE table_id IN (
        ${SCORE_TABLE_PASS_ID}, ${SCORE_TABLE_WARN_ID}, ${SCORE_TABLE_SEVERE_ID}, ${SCORE_TABLE_UNAVAIL_ID})`);
    psql(`DELETE FROM quality_check_detail WHERE rule_id IN (
        ${SCORE_RULE_PASS_1}, ${SCORE_RULE_PASS_2}, ${SCORE_RULE_WARN_1}, ${SCORE_RULE_WARN_PASS},
        ${SCORE_RULE_SEVERE_1}, ${SCORE_RULE_SEVERE_PASS}, ${SCORE_RULE_UNAVAIL})`);
    psql(`DELETE FROM quality_check_batch WHERE id IN (
        SELECT DISTINCT batch_id FROM quality_check_detail WHERE rule_id IN (
            ${SCORE_RULE_PASS_1}, ${SCORE_RULE_PASS_2}, ${SCORE_RULE_WARN_1}, ${SCORE_RULE_WARN_PASS},
            ${SCORE_RULE_SEVERE_1}, ${SCORE_RULE_SEVERE_PASS}, ${SCORE_RULE_UNAVAIL}))`);
    psql(`DELETE FROM quality_rule WHERE id IN (
        ${SCORE_RULE_PASS_1}, ${SCORE_RULE_PASS_2}, ${SCORE_RULE_WARN_1}, ${SCORE_RULE_WARN_PASS},
        ${SCORE_RULE_SEVERE_1}, ${SCORE_RULE_SEVERE_PASS}, ${SCORE_RULE_UNAVAIL})`);
    for (const id of [SCORE_TABLE_PASS_ID, SCORE_TABLE_WARN_ID, SCORE_TABLE_SEVERE_ID, SCORE_TABLE_UNAVAIL_ID]) {
        psql(`DELETE FROM metadata_column WHERE table_id = ${id}`);
        psql(`DELETE FROM metadata_table WHERE id = ${id}`);
    }
    quiet(mysqlExec, `DROP TABLE IF EXISTS ${SCORE_TABLE_PASS}, ${SCORE_TABLE_WARN}, ${SCORE_TABLE_SEVERE}, ${SCORE_TABLE_UNAVAIL}`);
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
    try {
        cleanupExecMetadata();
    } catch (e) {
        console.warn('sprint6 cleanup exec metadata:', ERR(e));
    }
    try {
        cleanupExecTables();
    } catch (e) {
        console.warn('sprint6 cleanup exec tables:', ERR(e));
    }
    try {
        cleanupQualityAlerts();
    } catch (e) {
        console.warn('sprint6 cleanup quality alerts:', ERR(e));
    }
    try {
        cleanupQualityScores();
    } catch (e) {
        console.warn('sprint6 cleanup quality scores:', ERR(e));
    }
}

/** 全量播种（globalSetup 调用，幂等） */
export async function seedAll(): Promise<void> {
    await ensureTestUsers();
    seedTemplates();
    seedQualityMetadata();
    seedSyncJob();
    seedExecTables();
    seedExecMetadata();
    seedQualityAlerts();
    seedQualityScores();
}
