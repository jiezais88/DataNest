import {Api} from '../../sprint6/helpers/api';
import {psqlDb, psqlEng, psqlGov, psqlSys} from './db';
import {
    ADMIN,
    TEST_USERS,
    DS_ID,
    DS_NAME,
    DB_NAME,
    T1_ID,
    T1_NAME,
    T1_COMMENT,
    T2_ID,
    T2_NAME,
    T2_COMMENT,
    T3_ID,
    T3_NAME,
    T3_COMMENT,
    T4_ID,
    T4_NAME,
    T4_COMMENT,
    T5_ID,
    T5_NAME,
    T5_COMMENT,
    D1_ID,
    D1_NAME,
    D1T1_ID,
    D1T1_NAME,
    D1T2_ID,
    D1T2_NAME,
    D2_ID,
    D2_NAME,
    SCORE_T1_ID,
    SCORE_T2_ID,
    SCORE_T3_ID,
    SCORE_T4_ID,
    RULE_R1_ID,
    RULE_R1_NAME,
    RULE_R2_ID,
    RULE_R2_NAME,
    RULE_R3_ID,
    RULE_R3_NAME,
    BATCH_ID,
    LINEAGE_UP_ID,
    LINEAGE_DOWN_ID,
    RESIDUE_CLASSIFICATION_IDS,
    RESIDUE_TABLE_IDS,
    TPL_SRC_JOB_ID,
    TPL_SRC_JOB_NAME,
    TPL_SRC_TABLE,
} from './data';

/**
 * Sprint 7 F1 数据资产目录测试数据播种/清理（全部幂等）。
 *
 * 播种内容：
 * - 3 个测试用户（s7_govadmin / s7_engineer / s7_analyst，经 API 创建，落 datanest_system）
 * - 1 个元数据数据源 e2e_s7_mysql_ds（datanest_engineering，仅元数据引用不真实执行）
 * - 5 张 metadata_table（T1 交易域·订单+负责人+有血缘有质量 / T2 交易域·订单 / T3 用户域 /
 *   T4 未分类 BAD / T5 内置 Doris 未分类）+ metadata_column
 * - 分类体系：e2e_s7_交易域（订单/退款两主题）+ e2e_s7_用户域
 * - quality_score 4 档（95 优秀 / 85 良好 / 70 一般 / 20 差）
 * - T1 的 3 条启用质量规则 + 1 批次 3 明细（PASS/PASS/WARNING，供详情页质量页签展示）
 * - lineage_record 2 条（T3 → T1 → T2）
 *
 * 同时清理 F1 开发自测残留（用户确认：交易域/用户域分类 + orders/order_items 的分类/负责人）。
 *
 * 注意：sprint5/6 的 seed 仍写旧 datanest 库（拆库后无效），本模块全部走拆库后的新库。
 */

const ERR = (e: unknown) => String(e).slice(0, 300);

const TABLE_IDS = [T1_ID, T2_ID, T3_ID, T4_ID, T5_ID];
const RULE_IDS = [RULE_R1_ID, RULE_R2_ID, RULE_R3_ID];
const SCORE_IDS = [SCORE_T1_ID, SCORE_T2_ID, SCORE_T3_ID, SCORE_T4_ID];

// ==================== 用户 ====================

/** 确保测试用户存在（幂等，sys_user 在 datanest_system），返回 userId */
async function ensureUser(api: Api, u: {
    username: string;
    password: string;
    roles: string[];
    email: string
}): Promise<string> {
    const existing = psqlSys(`SELECT id FROM sys_user WHERE username = '${u.username}'`);
    if (existing) {
        if (u.email) {
            psqlSys(`UPDATE sys_user SET email='${u.email}' WHERE username = '${u.username}'`);
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

export function deleteTestUsers(): void {
    for (const u of Object.values(TEST_USERS)) {
        psqlSys(`DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username = '${u.username}')`);
        psqlSys(`DELETE FROM sys_user WHERE username = '${u.username}'`);
    }
}

// ==================== F1 开发自测残留清理（用户确认清理后重建） ====================

export function cleanupResidue(): void {
    // 先重置表引用，再按 主题 → 域 顺序删分类（避免删除校验拦截）
    psqlGov(`UPDATE metadata_table SET data_domain=NULL, data_topic=NULL, owner_user_id=NULL
             WHERE id IN (${RESIDUE_TABLE_IDS.join(',')})`);
    for (const id of RESIDUE_CLASSIFICATION_IDS) {
        psqlGov(`DELETE FROM asset_classification WHERE id = ${id}`);
    }
}

// ==================== 数据源（datanest_engineering） ====================

export function seedDatasource(): void {
    psqlEng(`DELETE FROM datasource_connection WHERE id = ${DS_ID}`);
    psqlEng(`
        INSERT INTO datasource_connection
        (id, name, type, host, port, database_name, schema_name, username, encrypted_password,
         status, created_at, updated_at, auto_collect_on_save)
        VALUES (${DS_ID}, '${DS_NAME}', 'MYSQL', 'middleware-test-mysql', 3306,
                '${DB_NAME}', NULL, 'testuser', 'x', 'NORMAL', now(), now(), 0)
    `);
}

// ==================== 元数据表 / 字段 / 分类（datanest_governance） ====================

export function seedMetadata(): void {
    // 清理历史（先列后表；分类按名前缀兜底清 UI 测试产生的新节点）
    for (const id of TABLE_IDS) {
        psqlGov(`DELETE FROM metadata_column WHERE table_id = ${id}`);
        psqlGov(`DELETE FROM metadata_table WHERE id = ${id}`);
    }
    psqlGov(`DELETE FROM asset_classification WHERE name LIKE 'e2e_s7%'`);

    // 分类体系：交易域（订单/退款）+ 用户域
    psqlGov(`
        INSERT INTO asset_classification (id, level, name, parent_id, sort, created_by, created_at)
        VALUES
        (${D1_ID}, 'DOMAIN', '${D1_NAME}', NULL, 1, 1, now()),
        (${D1T1_ID}, 'TOPIC', '${D1T1_NAME}', ${D1_ID}, 1, 1, now()),
        (${D1T2_ID}, 'TOPIC', '${D1T2_NAME}', ${D1_ID}, 2, 1, now()),
        (${D2_ID}, 'DOMAIN', '${D2_NAME}', NULL, 2, 1, now())
    `);

    // T1 负责人 = s7_analyst（负责人搜索维度命中）
    const analystId = psqlSys(`SELECT id FROM sys_user WHERE username = '${TEST_USERS.analyst.username}'`);

    // 元数据表（T1/T2 → 交易域·订单；T3 → 用户域；T4 未分类；T5 内置 Doris 未分类）
    psqlGov(`
        INSERT INTO metadata_table
        (id, datasource_id, database_name, schema_name, table_name, table_comment,
         source_status, source_type, data_domain, data_topic, owner_user_id, created_at, updated_at)
        VALUES
        (${T1_ID}, ${DS_ID}, '${DB_NAME}', NULL, '${T1_NAME}', '${T1_COMMENT}',
         'ONLINE', 'EXTERNAL', '${D1_NAME}', '${D1T1_NAME}', ${analystId}, now(), now()),
        (${T2_ID}, ${DS_ID}, '${DB_NAME}', NULL, '${T2_NAME}', '${T2_COMMENT}',
         'ONLINE', 'EXTERNAL', '${D1_NAME}', '${D1T1_NAME}', NULL, now(), now()),
        (${T3_ID}, ${DS_ID}, '${DB_NAME}', NULL, '${T3_NAME}', '${T3_COMMENT}',
         'ONLINE', 'EXTERNAL', '${D2_NAME}', NULL, NULL, now(), now()),
        (${T4_ID}, ${DS_ID}, '${DB_NAME}', NULL, '${T4_NAME}', '${T4_COMMENT}',
         'ONLINE', 'EXTERNAL', NULL, NULL, NULL, now(), now()),
        (${T5_ID}, -1, 'datanest', NULL, '${T5_NAME}', '${T5_COMMENT}',
         'ONLINE', 'BUILTIN_DORIS', NULL, NULL, NULL, now(), now())
    `);

    // 元数据列（T1 三列含「交易流水号」注释供字段维度搜索；字段数指标卡=3）
    psqlGov(`
        INSERT INTO metadata_column
        (id, table_id, column_name, data_type, column_comment, nullable, ordinal_position,
         source_type, source_status, created_at, updated_at)
        VALUES
        (9000070000000000101, ${T1_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000102, ${T1_ID}, 'trade_no', 'varchar', '交易流水号', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000103, ${T1_ID}, 'amount', 'decimal', '订单金额', true, 3, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000111, ${T2_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000112, ${T2_ID}, 'refund_no', 'varchar', '退款单号', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000121, ${T3_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000122, ${T3_ID}, 'user_id', 'bigint', '用户ID', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000131, ${T4_ID}, 'id', 'bigint', '主键', false, 1, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000132, ${T4_ID}, 'sku_code', 'varchar', 'SKU编码', true, 2, 'EXTERNAL', 'ONLINE', now(), now()),
        (9000070000000000141, ${T5_ID}, 'id', 'bigint', '主键', false, 1, 'BUILTIN_DORIS', 'ONLINE', now(), now())
    `);
}

// ==================== 质量评分 / 规则 / 批次（datanest_governance） ====================

export function seedQuality(): void {
    // 清理历史（明细 → 批次 → 规则 → 评分）
    psqlGov(`DELETE FROM quality_check_detail WHERE table_id IN (${TABLE_IDS.join(',')}) OR rule_id IN (${RULE_IDS.join(',')})`);
    psqlGov(`DELETE FROM quality_check_batch WHERE id = ${BATCH_ID} OR job_name LIKE 'e2e_s7%'`);
    psqlGov(`DELETE FROM quality_rule WHERE id IN (${RULE_IDS.join(',')})`);
    psqlGov(`DELETE FROM quality_score WHERE id IN (${SCORE_IDS.join(',')}) OR table_id IN (${TABLE_IDS.join(',')})`);

    // 评分 4 档（健康度筛选 / sort=score / 详情页指标卡用）
    psqlGov(`
        INSERT INTO quality_score
        (id, table_id, table_name, datasource_id, score, health_level,
         pass_rules, warning_rules, severe_rules, last_checked_at, updated_at)
        VALUES
        (${SCORE_T1_ID}, ${T1_ID}, '${T1_NAME}', ${DS_ID}, 95.00, 'EXCELLENT', 2, 1, 0, now(), now()),
        (${SCORE_T2_ID}, ${T2_ID}, '${T2_NAME}', ${DS_ID}, 85.00, 'GOOD', 2, 0, 0, now(), now()),
        (${SCORE_T3_ID}, ${T3_ID}, '${T3_NAME}', ${DS_ID}, 70.00, 'WARNING', 1, 1, 0, now(), now()),
        (${SCORE_T4_ID}, ${T4_ID}, '${T4_NAME}', ${DS_ID}, 20.00, 'BAD', 0, 0, 1, now(), now())
    `);

    // T1 的 3 条启用规则（质量页签规则表 + 启用规则数=3）
    psqlGov(`
        INSERT INTO quality_rule
        (id, name, type, table_id, column_name, check_field, sql_expression, result_metric,
         warning_threshold, severe_threshold, weight, enabled, created_at, updated_at)
        VALUES
        (${RULE_R1_ID}, '${RULE_R1_NAME}', 'COMPLETENESS', ${T1_ID}, 'amount', 1,
         'SELECT 0 AS null_rate', 'null_rate', 0.1, 0.5, 1, 1, now(), now()),
        (${RULE_R2_ID}, '${RULE_R2_NAME}', 'UNIQUENESS', ${T1_ID}, 'trade_no', 1,
         'SELECT 0 AS duplicate_count', 'duplicate_count', 0, 1, 1, 1, now(), now()),
        (${RULE_R3_ID}, '${RULE_R3_NAME}', 'RANGE', ${T1_ID}, 'amount', 1,
         'SELECT 0.5 AS out_of_range_rate', 'out_of_range_rate', 0.1, 0.9, 2, 1, now(), now())
    `);

    // 1 批次 + 3 明细（PASS/PASS/WARNING，质量页签「最近结果/判定」展示用）
    psqlGov(`
        INSERT INTO quality_check_batch
        (id, job_id, job_name, trigger_type, status, started_at, ended_at, duration_ms, created_at, alert_sent)
        VALUES (${BATCH_ID}, NULL, 'e2e_s7 质量页签批次', 'MANUAL', 'SUCCESS', now(), now(), 100, now(), 0)
    `);
    psqlGov(`
        INSERT INTO quality_check_detail
        (id, batch_id, rule_id, rule_name, rule_type, table_id, result_metric, result_value,
         success, executed_sql, created_at, result_level)
        VALUES
        (9000070000000000052, ${BATCH_ID}, ${RULE_R1_ID}, '${RULE_R1_NAME}', 'COMPLETENESS', ${T1_ID},
         'null_rate', 0, 1, 'SELECT 0 AS null_rate', now(), 'PASS'),
        (9000070000000000053, ${BATCH_ID}, ${RULE_R2_ID}, '${RULE_R2_NAME}', 'UNIQUENESS', ${T1_ID},
         'duplicate_count', 0, 1, 'SELECT 0 AS duplicate_count', now(), 'PASS'),
        (9000070000000000054, ${BATCH_ID}, ${RULE_R3_ID}, '${RULE_R3_NAME}', 'RANGE', ${T1_ID},
         'out_of_range_rate', 0.5, 1, 'SELECT 0.5 AS out_of_range_rate', now(), 'WARNING')
    `);
}

// ==================== 血缘（datanest_governance.lineage_record） ====================

export function seedLineage(): void {
    psqlGov(`DELETE FROM lineage_record WHERE id IN (${LINEAGE_UP_ID}, ${LINEAGE_DOWN_ID})`);
    psqlGov(`
        INSERT INTO lineage_record
        (id, source_table, target_table, source_column, target_column,
         dag_id, dag_name, node_id, node_name, execution_id, lineage_type, created_at)
        VALUES
        (${LINEAGE_UP_ID}, '${DB_NAME}.${T3_NAME}', '${DB_NAME}.${T1_NAME}', NULL, NULL,
         1, 'e2e_s7_dag', 'n1', 'SQL节点', 1, 'SQL', NOW()),
        (${LINEAGE_DOWN_ID}, '${DB_NAME}.${T1_NAME}', '${DB_NAME}.${T2_NAME}', NULL, NULL,
         1, 'e2e_s7_dag', 'n2', 'SQL节点', 1, 'SQL', NOW())
    `);
}

// ==================== F2 任务模板库 fixture（datanest_engineering.sync_job） ====================

/** 「另存为」候选同步任务（幂等）；单表 SYNC，另存为时后端自动抽 {source_table} 占位符 */
export function seedTaskTemplateFixtures(): void {
    psqlEng(`DELETE FROM sync_job WHERE id = ${TPL_SRC_JOB_ID}`);
    psqlEng(`
        INSERT INTO sync_job
        (id, name, source_datasource_id, source_database, source_tables, sync_mode, trigger_type,
         target_database, target_table, created_by, created_at)
        VALUES (${TPL_SRC_JOB_ID}, '${TPL_SRC_JOB_NAME}', ${DS_ID}, '${DB_NAME}', '["${TPL_SRC_TABLE}"]',
                'FULL', 'MANUAL', 'dwd', 'e2e_s7_tgt_orders', 1, now())
    `);
}

/** F2 测试产生的模板/任务清理（幂等；任务先删，模板后删） */
export function cleanupTaskTemplateFixtures(): void {
    psqlEng(`DELETE FROM sync_job WHERE id = ${TPL_SRC_JOB_ID} OR name LIKE 'e2e_s7%'`);
    psqlEng(`DELETE FROM task_template WHERE name LIKE 'e2e_s7%'`);
    psqlGov(`DELETE FROM collect_task WHERE name LIKE 'e2e_s7%'`);
}

// ==================== 清理 / 播种 ====================

/** 清理全部 Sprint 7 测试数据（幂等） */
export async function cleanupAll(): Promise<void> {
    try {
        psqlGov(`DELETE FROM quality_check_detail WHERE table_id IN (${TABLE_IDS.join(',')}) OR rule_id IN (${RULE_IDS.join(',')})`);
        psqlGov(`DELETE FROM quality_check_batch WHERE id = ${BATCH_ID} OR job_name LIKE 'e2e_s7%'`);
        psqlGov(`DELETE FROM quality_rule WHERE id IN (${RULE_IDS.join(',')}) OR name LIKE 'e2e_s7%'`);
        psqlGov(`DELETE FROM quality_score WHERE id IN (${SCORE_IDS.join(',')}) OR table_id IN (${TABLE_IDS.join(',')})`);
        psqlGov(`DELETE FROM lineage_record WHERE id IN (${LINEAGE_UP_ID}, ${LINEAGE_DOWN_ID})`);
        for (const id of TABLE_IDS) {
            psqlGov(`DELETE FROM metadata_column WHERE table_id = ${id}`);
            psqlGov(`DELETE FROM metadata_table WHERE id = ${id}`);
        }
        psqlGov(`DELETE FROM asset_classification WHERE name LIKE 'e2e_s7%'`);
        psqlEng(`DELETE FROM datasource_connection WHERE id = ${DS_ID}`);
        cleanupTaskTemplateFixtures();
    } catch (e) {
        console.warn('sprint7 cleanup gov/eng:', ERR(e));
    }
    try {
        deleteTestUsers();
    } catch (e) {
        console.warn('sprint7 cleanup users:', ERR(e));
    }
}

/** 全量播种（globalSetup 调用 + spec 自带播种，幂等） */
export async function seedAll(): Promise<void> {
    await ensureTestUsers();
    cleanupResidue();
    seedDatasource();
    seedMetadata();
    seedQuality();
    seedLineage();
    cleanupTaskTemplateFixtures();
    seedTaskTemplateFixtures();
}
