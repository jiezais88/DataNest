import {Api} from '../../sprint6/helpers/api';
import {mysqlT, pgT, psqlRt, psqlAlert, psqlSys, mailhogDeleteAll, doris} from './db';
import {ADMIN, TEST_USERS, TARGET_DB, T_MAIN, T_FAIL, T_EXT, T_PG, T_PG_NO_FULL} from './data';

/**
 * Sprint 9 E2E 播种/清理（全部幂等）。
 *
 * 播种内容：
 * - 测试用户复用 sprint7（seedAll 已建），本模块仅确保存在（幂等）
 * - 重建 MySQL 源表 e2e_s9_cdc_users（快照 3 行）
 * - 重建 PG 源表 e2e_s9_pg_users（REPLICA IDENTITY FULL）+ e2e_s9_pg_no_full（未开启，警示验证）
 * - 清理 e2e_s9_* 管道（先停 Flink 作业再删库记录，级联清 savepoint 文件）
 * - 清理 e2e_s9_* 告警规则（alert_rule / alert_rule_object / alert_rule_user）
 * - 清空 MailHog
 */

const ERR = (e: unknown) => String(e).slice(0, 300);

/** 确保测试用户存在（复用 sprint7 seed 的用户；存在则更新邮箱，不存在则 API 创建） */
export async function ensureTestUsers(): Promise<void> {
    const api = await Api.create();
    await api.login(ADMIN.username, ADMIN.password);
    for (const u of Object.values(TEST_USERS)) {
        const existing = psqlSys(`SELECT id FROM sys_user WHERE username = '${u.username}'`);
        if (existing) {
            psqlSys(`UPDATE sys_user SET email = '${u.email}' WHERE username = '${u.username}'`);
            continue;
        }
        await api.post('/system/users', {
            username: u.username,
            password: u.password,
            roles: u.roles,
            email: u.email,
        });
    }
    await api.dispose();
}

/** 重建源库测试表（幂等，保证行数基线） */
export function seedSourceTables(): void {
    mysqlT(`DROP TABLE IF EXISTS ${T_MAIN};
            CREATE TABLE ${T_MAIN} (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(64) NOT NULL,
                amount INT DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id));
            INSERT INTO ${T_MAIN} (username, amount) VALUES ('seed_a', 100), ('seed_b', 200), ('seed_c', 300);
            DROP TABLE IF EXISTS ${T_FAIL};
            CREATE TABLE ${T_FAIL} (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(64) NOT NULL,
                amount INT DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id));
            INSERT INTO ${T_FAIL} (username, amount) VALUES ('f_a', 1), ('f_b', 2), ('f_c', 3);
            DROP TABLE IF EXISTS ${T_EXT};
            CREATE TABLE ${T_EXT} (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(64) NOT NULL,
                amount INT DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id));
            INSERT INTO ${T_EXT} (username, amount) VALUES ('e_a', 1), ('e_b', 2), ('e_c', 3);`);
    pgT(`DROP TABLE IF EXISTS ${T_PG};
         CREATE TABLE ${T_PG} (id BIGINT PRIMARY KEY, name VARCHAR(64), updated_at TIMESTAMP DEFAULT now());
         ALTER TABLE ${T_PG} REPLICA IDENTITY FULL;
         INSERT INTO ${T_PG} (id, name) VALUES (1, 'pg_seed_a'), (2, 'pg_seed_b');
         DROP TABLE IF EXISTS ${T_PG_NO_FULL};
         CREATE TABLE ${T_PG_NO_FULL} (id BIGINT PRIMARY KEY, name VARCHAR(64));`);
}

/** 清理全部 e2e_s9 管道（先停 Flink 作业再删库记录，幂等） */
export async function cleanPipelines(admin: Api): Promise<void> {
    try {
        const running = psqlRt(`SELECT id FROM cdc_pipeline WHERE name LIKE 'e2e_s9_%' AND status = 'RUNNING'`);
        for (const id of running.split('\n').filter(Boolean)) {
            await admin.raw('POST', `/realtime/cdc/pipelines/${id}/stop`).catch(() => undefined);
        }
    } catch { /* 无残留 */ }
    psqlRt(`DELETE FROM cdc_pipeline_log WHERE pipeline_id IN (SELECT id FROM cdc_pipeline WHERE name LIKE 'e2e_s9_%');
            DELETE FROM cdc_pipeline_table WHERE pipeline_id IN (SELECT id FROM cdc_pipeline WHERE name LIKE 'e2e_s9_%');
            DELETE FROM cdc_pipeline WHERE name LIKE 'e2e_s9_%';`);
}

/** 清理 e2e_s9 告警规则（alert 库三表） */
export function cleanAlertRules(): void {
    psqlAlert(`DELETE FROM alert_rule_user WHERE alert_rule_id IN (SELECT id FROM alert_rule WHERE name LIKE 'e2e_s9_%');
               DELETE FROM alert_rule_object WHERE alert_rule_id IN (SELECT id FROM alert_rule WHERE name LIKE 'e2e_s9_%');
               DELETE FROM alert_history WHERE alert_rule_id IN (SELECT id FROM alert_rule WHERE name LIKE 'e2e_s9_%');
               DELETE FROM alert_rule WHERE name LIKE 'e2e_s9_%';`);
}

/** 清理 Doris 湖仓残留（best effort） */
export function cleanLake(): void {
    for (const t of [T_MAIN, T_FAIL, T_EXT, T_PG]) {
        try {
            doris(`DROP TABLE IF EXISTS datalake_catalog.${TARGET_DB}.${t}`);
        } catch { /* best effort */ }
    }
}

/** 丢弃源表（best effort） */
export function dropSourceTables(): void {
    try {
        mysqlT(`DROP TABLE IF EXISTS ${T_MAIN}; DROP TABLE IF EXISTS ${T_FAIL}; DROP TABLE IF EXISTS ${T_EXT};`);
        pgT(`DROP TABLE IF EXISTS ${T_PG}; DROP TABLE IF EXISTS ${T_PG_NO_FULL};`);
    } catch { /* best effort */ }
}

/** 全量清理（beforeAll 前置 + 测试末尾调用） */
export async function cleanupAll(admin: Api): Promise<void> {
    try {
        await cleanPipelines(admin);
    } catch (e) {
        console.warn('sprint9 cleanup pipelines:', ERR(e));
    }
    try {
        cleanAlertRules();
    } catch (e) {
        console.warn('sprint9 cleanup alert:', ERR(e));
    }
    try {
        cleanLake();
    } catch (e) {
        console.warn('sprint9 cleanup lake:', ERR(e));
    }
    try {
        mailhogDeleteAll();
    } catch (e) {
        console.warn('sprint9 cleanup mail:', ERR(e));
    }
}
