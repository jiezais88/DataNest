import {execSync} from 'child_process';
import {Api} from '../../../sprint6/helpers/api';

/**
 * Sprint 13 自定义 SQL E2E 测试数据辅助：自播种自清理。
 *
 * 数据策略（用户确认 2026-08-17，t6 预研）：
 * - Doris 测试表 3 张（内置 Doris datasourceId=-1，库 datanest），经 Doris FE REST
 *   （POST /api/query/internal/{db}，root/password，stmt JSON）建表插数：
 *   e2e_s13_orders（6 行，金额已知）/ e2e_s13_region（3 行 EAST/SOUTH/NORTH）/
 *   e2e_s13_big（2187 行，测 1000 行截断）。区域名用 ASCII（Doris REST 中文会乱码）。
 * - 元数据：直接 SQL 插 metadata_table/metadata_column（BUILTIN_DORIS、PUBLIC），
 *   敏感度闸门用例直接 UPDATE 改级测完复位。
 * - 临时用户 e2e_s13_engineer（自定义角色 E2E_S13_ROLE，数据权限 WHITELIST 仅
 *   demo_ecommerce.ods_orders，外部 Doris 数据源 2089276945965502465 → 造 fail-closed）；
 *   e2e_s13_analyst（DATA_ANALYST 只读）。
 * - API/Key 前缀 e2e_s13_ / 路径段 e2e-s13-；cleanup 物理删除 + 清血缘/审计/调用日志。
 */

export const ADMIN = {username: 'admin', password: 'admin123'};
export const PW = 'Test123456';

/** 内置 Doris 常量 */
export const BUILTIN_DORIS_ID = -1;
export const EXTERNAL_DORIS_ID = '2089276945965502465'; // demo-电商数仓-Doris（权限闸门用；超 2^53 必须字符串防 JS Number 精度丢失，见 AGENTS.md 已知坑）
export const DORIS_DB = 'datanest';
export const EXTERNAL_DB = 'demo_ecommerce';

export const PREFIX = 'e2e_s13_';
export const PATH_PREFIX = 'e2e-s13-';
export const ROLE_CODE = 'E2E_S13_ROLE';

/** 元数据表 ID（种子固定；超 Long 安全整数范围，必须用字符串防 JS Number 精度丢失） */
export const ORDERS_META_ID = '9000130000000000001';
export const REGION_META_ID = '9000130000000000002';

/** 主链路 SQL（JOIN+聚合+参数，PRD §6.2 样例；DESC 排序供分页序断言） */
export const MAIN_SQL = `SELECT r.region_name AS region_name, SUM(o.amount) AS total
FROM datanest.e2e_s13_orders o
JOIN datanest.e2e_s13_region r ON o.region_id = r.region_id
WHERE o.order_date >= :startDate
GROUP BY r.region_name
ORDER BY total DESC`;

/** 已知数据口径（startDate=2026-01-01 时全量） */
export const EXPECTED_AGG = [
    {region_name: 'SOUTH', total: 400},
    {region_name: 'NORTH', total: 350},
    {region_name: 'EAST', total: 300},
];

// ==================== DB 直连辅助 ====================

function psql(db: string, sql: string): string {
    return execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

export const psqlDs = (sql: string): string => psql('datanest_dataservice', sql);
export const psqlGov = (sql: string): string => psql('datanest_governance', sql);
export const psqlSys = (sql: string): string => psql('datanest_system', sql);
export const psqlEng = (sql: string): string => psql('datanest_engineering', sql);

// ==================== Doris REST（Node fetch，Windows 可用） ====================

const DORIS_FE = 'http://192.168.119.135:8030';
const DORIS_AUTH = `Basic ${Buffer.from('root:password').toString('base64')}`;

export async function dorisStmt(db: string, stmt: string): Promise<any> {
    const res = await fetch(`${DORIS_FE}/api/query/internal/${db}`, {
        method: 'POST',
        headers: {Authorization: DORIS_AUTH, 'Content-Type': 'application/json'},
        body: JSON.stringify({stmt}),
        signal: AbortSignal.timeout(120_000),
    });
    const j = await res.json();
    if (j.code !== 0) throw new Error(`Doris stmt err(${res.status}): ${JSON.stringify(j)}`);
    return j;
}

// ==================== 播种 ====================

/** Doris 建表插数（幂等：DROP IF EXISTS → CREATE → INSERT） */
export async function seedDorisTables(): Promise<void> {
    await dorisStmt(DORIS_DB, 'DROP TABLE IF EXISTS e2e_s13_orders');
    await dorisStmt(DORIS_DB, 'DROP TABLE IF EXISTS e2e_s13_region');
    await dorisStmt(DORIS_DB, 'DROP TABLE IF EXISTS e2e_s13_big');
    await dorisStmt(DORIS_DB,
        'CREATE TABLE IF NOT EXISTS e2e_s13_orders (order_id BIGINT, region_id BIGINT, amount DECIMAL(12,2), order_date DATE, remark VARCHAR(50)) DUPLICATE KEY(order_id) DISTRIBUTED BY HASH(order_id) BUCKETS 1 PROPERTIES ("replication_num" = "1")');
    await dorisStmt(DORIS_DB,
        'CREATE TABLE IF NOT EXISTS e2e_s13_region (region_id BIGINT, region_name VARCHAR(20)) DUPLICATE KEY(region_id) DISTRIBUTED BY HASH(region_id) BUCKETS 1 PROPERTIES ("replication_num" = "1")');
    await dorisStmt(DORIS_DB,
        'CREATE TABLE IF NOT EXISTS e2e_s13_big (id BIGINT, val VARCHAR(10)) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")');
    await dorisStmt(DORIS_DB, "INSERT INTO e2e_s13_region VALUES (1,'EAST'),(2,'SOUTH'),(3,'NORTH')");
    await dorisStmt(DORIS_DB, "INSERT INTO e2e_s13_orders VALUES (1,1,100.00,'2026-01-10','R1'),(2,1,200.00,'2026-02-10','R2'),(3,2,150.00,'2026-01-15','R3'),(4,2,250.00,'2026-03-01','R4'),(5,3,300.00,'2026-02-20','R5'),(6,3,50.00,'2026-01-05','R6')");
    await dorisStmt(DORIS_DB,
        'INSERT INTO e2e_s13_big (id) SELECT t1.region_id + t2.region_id*10 + t3.region_id*100 + t4.region_id*1000 + t5.region_id*10000 + t6.region_id*100000 + t7.region_id*1000000 FROM datanest.e2e_s13_region t1 CROSS JOIN datanest.e2e_s13_region t2 CROSS JOIN datanest.e2e_s13_region t3 CROSS JOIN datanest.e2e_s13_region t4 CROSS JOIN datanest.e2e_s13_region t5 CROSS JOIN datanest.e2e_s13_region t6 CROSS JOIN datanest.e2e_s13_region t7');
}

/** 元数据（governance）：e2e_s13_orders / e2e_s13_region，PUBLIC + BUILTIN_DORIS */
export function seedMetadata(): void {
    psqlGov(`
        DELETE FROM metadata_column WHERE table_id IN (SELECT id FROM metadata_table WHERE table_name LIKE '${PREFIX}%');
        DELETE FROM metadata_table WHERE table_name LIKE '${PREFIX}%';
        INSERT INTO metadata_table (id, datasource_id, database_name, schema_name, table_name, table_comment, source_status, source_type, sensitivity_level, api_exempted, created_at, updated_at) VALUES
         (${ORDERS_META_ID}, -1, '${DORIS_DB}', NULL, 'e2e_s13_orders', 'Sprint13 E2E 订单表', 'ONLINE', 'BUILTIN_DORIS', 'PUBLIC', 0, now(), now()),
         (${REGION_META_ID}, -1, '${DORIS_DB}', NULL, 'e2e_s13_region', 'Sprint13 E2E 区域表', 'ONLINE', 'BUILTIN_DORIS', 'PUBLIC', 0, now(), now());
        INSERT INTO metadata_column (id, table_id, column_name, data_type, column_comment, nullable, ordinal_position, source_type, source_status, created_at, updated_at) VALUES
         ('9000130000000000101', ${ORDERS_META_ID}, 'order_id', 'bigint', '订单ID', false, 1, 'BUILTIN_DORIS', 'ONLINE', now(), now()),
         ('9000130000000000102', ${ORDERS_META_ID}, 'region_id', 'bigint', '区域ID', true, 2, 'BUILTIN_DORIS', 'ONLINE', now(), now()),
         ('9000130000000000103', ${ORDERS_META_ID}, 'amount', 'decimal', '订单金额', true, 3, 'BUILTIN_DORIS', 'ONLINE', now(), now()),
         ('9000130000000000104', ${ORDERS_META_ID}, 'order_date', 'date', '订单日期', true, 4, 'BUILTIN_DORIS', 'ONLINE', now(), now()),
         ('9000130000000000105', ${ORDERS_META_ID}, 'remark', 'varchar', '备注', true, 5, 'BUILTIN_DORIS', 'ONLINE', now(), now()),
         ('9000130000000000111', ${REGION_META_ID}, 'region_id', 'bigint', '区域ID', false, 1, 'BUILTIN_DORIS', 'ONLINE', now(), now()),
         ('9000130000000000112', ${REGION_META_ID}, 'region_name', 'varchar', '区域名', true, 2, 'BUILTIN_DORIS', 'ONLINE', now(), now());
    `);
}

/** 敏感度闸门造数（orders 表）：level=PUBLIC|INTERNAL|CONFIDENTIAL，exempt=0|1 */
export function setOrdersSensitivity(level: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL', exempt: 0 | 1 = 0): void {
    psqlGov(`UPDATE metadata_table SET sensitivity_level='${level}', api_exempted=${exempt} WHERE table_name='e2e_s13_orders'`);
}

/** 临时用户/角色/数据权限（engineer WHITELIST 仅 ods_orders；analyst 只读） */
export async function seedUsers(): Promise<{engineerId: string; analystId: string}> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    try {
        // 角色（幂等：先物理清残留）
        psqlSys(`
            DELETE FROM sys_user_role WHERE role_id IN (SELECT id FROM sys_role WHERE code='${ROLE_CODE}');
            DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code='${ROLE_CODE}');
            DELETE FROM sys_data_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code='${ROLE_CODE}');
            DELETE FROM sys_role WHERE code='${ROLE_CODE}';
        `);
        const role = await api.post<{id: string}>('/system/roles', {
            name: `${PREFIX}role`, code: ROLE_CODE, description: 'Sprint13 E2E 自定义角色',
            permissions: ['datasource:view', 'sql:execute', 'asset:view', 'api:create'],
        });
        const roleId = String(role.id);

        // 用户
        psqlSys(`DELETE FROM sys_user WHERE username IN ('${PREFIX}engineer','${PREFIX}analyst')`);
        const eng = await api.post<{id: string}>('/system/users', {
            username: `${PREFIX}engineer`, password: PW, roles: [ROLE_CODE], email: `${PREFIX}engineer@test.io`,
        });
        const ana = await api.post<{id: string}>('/system/users', {
            username: `${PREFIX}analyst`, password: PW, roles: ['DATA_ANALYST'], email: `${PREFIX}analyst@test.io`,
        });

        // 数据权限：WHITELIST 白名单仅 ods_orders（角色维度，供 fail-closed 多表用例）
        await api.post('/system/data-permissions', {
            roleId, dataScope: 'WHITELIST',
            grants: [{datasourceId: String(EXTERNAL_DORIS_ID), databaseName: EXTERNAL_DB, tableName: 'ods_orders'}],
        });
        return {engineerId: String(eng.id), analystId: String(ana.id)};
    } finally {
        await api.dispose();
    }
}

/** 全量播种（beforeAll）：Doris 表 + 元数据 + 用户 */
export async function seedS13(): Promise<void> {
    cleanupApisAndKeys();
    await seedDorisTables();
    seedMetadata();
    await seedUsers();
}

// ==================== 清理 ====================

/** 物理删除测试 API/Key/绑定/调用日志（前缀命中，含残留） */
export function cleanupApisAndKeys(): void {
    psqlDs(`
        DELETE FROM api_call_log
        WHERE api_id IN (SELECT id FROM data_api WHERE name LIKE '${PREFIX}%')
           OR key_id IN (SELECT id FROM api_key WHERE name LIKE '${PREFIX}%');
        DELETE FROM api_key_binding
        WHERE key_id IN (SELECT id FROM api_key WHERE name LIKE '${PREFIX}%')
           OR api_id IN (SELECT id FROM data_api WHERE name LIKE '${PREFIX}%');
        DELETE FROM api_key WHERE name LIKE '${PREFIX}%' OR name LIKE 'e2e-s13-%';
        DELETE FROM data_api WHERE name LIKE '${PREFIX}%';
    `);
}

/** 清理血缘 + 审计 + 元数据 + 用户/角色 */
export function cleanupMetaAndUsers(): void {
    psqlGov(`
        DELETE FROM lineage_record WHERE dag_name LIKE '${PREFIX}%' OR source_table LIKE '%e2e_s13%' OR target_table LIKE '%e2e_s13%';
        DELETE FROM metadata_column WHERE table_id IN (SELECT id FROM metadata_table WHERE table_name LIKE '${PREFIX}%');
        DELETE FROM metadata_table WHERE table_name LIKE '${PREFIX}%';
    `);
    psqlSys(`
        DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE '${PREFIX}%');
        DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code='${ROLE_CODE}');
        DELETE FROM sys_data_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code='${ROLE_CODE}');
        DELETE FROM sys_user WHERE username LIKE '${PREFIX}%';
        DELETE FROM sys_role WHERE code='${ROLE_CODE}';
        DELETE FROM audit_log WHERE resource_name LIKE '${PREFIX}%';
    `);
}

/** 全量清理（afterAll）：API/Key + 血缘/审计/元数据 + 用户 + Doris 表（自清理） */
export async function cleanupS13(): Promise<void> {
    try {
        cleanupApisAndKeys();
        cleanupMetaAndUsers();
        await dorisStmt(DORIS_DB, 'DROP TABLE IF EXISTS e2e_s13_orders');
        await dorisStmt(DORIS_DB, 'DROP TABLE IF EXISTS e2e_s13_region');
        await dorisStmt(DORIS_DB, 'DROP TABLE IF EXISTS e2e_s13_big');
    } catch (e) {
        console.warn('[S13 cleanup] 清理异常（可忽略，人工复查）:', e);
    }
}
