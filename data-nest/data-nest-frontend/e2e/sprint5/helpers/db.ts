import {execSync} from 'child_process';

/**
 * 直接执行 PostgreSQL 业务库查询/写入（用于播种与断言）
 * 通过 docker exec -i 传 stdin，避免 Windows 下引号转义问题
 *
 * 拆库适配（2026-08-07 起）：旧 datanest 库已冻结，业务表按域拆到 4 个库。
 * psql/scalar/rows 按 SQL 中引用的业务表自动路由到对应域库；
 * 跨库语句（拆库后不允许 JOIN/子查询跨库）会直接抛错，需拆成两步用 psqlDb/psql* 显式指定库。
 */
export type BizDb = 'datanest_governance' | 'datanest_engineering' | 'datanest_system' | 'datanest_alert';

/** 表 → 域库映射（对照各服务 db/migration/V1.0.0__baseline.sql 的表归属） */
const TABLE_DB: Record<string, BizDb> = {
    // datanest_governance（app-governance）
    asset_classification: 'datanest_governance',
    asset_comment: 'datanest_governance',
    asset_favorite: 'datanest_governance',
    asset_follow: 'datanest_governance',
    asset_table_tag: 'datanest_governance',
    asset_tag: 'datanest_governance',
    asset_view_log: 'datanest_governance',
    collect_change_detail: 'datanest_governance',
    collect_execution_log: 'datanest_governance',
    collect_history: 'datanest_governance',
    collect_task: 'datanest_governance',
    compliance_check_result: 'datanest_governance',
    field_type_standard: 'datanest_governance',
    lineage_record: 'datanest_governance',
    metadata_column: 'datanest_governance',
    metadata_table: 'datanest_governance',
    naming_standard: 'datanest_governance',
    quality_check_batch: 'datanest_governance',
    quality_check_detail: 'datanest_governance',
    quality_job: 'datanest_governance',
    quality_job_rule: 'datanest_governance',
    quality_rule: 'datanest_governance',
    quality_rule_template: 'datanest_governance',
    quality_score: 'datanest_governance',
    quality_score_config: 'datanest_governance',
    // datanest_engineering（app-engineering）
    dag: 'datanest_engineering',
    dag_edge: 'datanest_engineering',
    dag_execution: 'datanest_engineering',
    dag_node: 'datanest_engineering',
    dag_parameter: 'datanest_engineering',
    dag_project: 'datanest_engineering',
    dag_version: 'datanest_engineering',
    datasource_connection: 'datanest_engineering',
    node_execution: 'datanest_engineering',
    node_execution_log: 'datanest_engineering',
    sync_job: 'datanest_engineering',
    sync_job_history: 'datanest_engineering',
    sync_job_log: 'datanest_engineering',
    // datanest_alert（app-alert）
    alert_history: 'datanest_alert',
    alert_rule: 'datanest_alert',
    alert_rule_object: 'datanest_alert',
    alert_rule_user: 'datanest_alert',
    dag_alert_config: 'datanest_alert',
    dag_alert_history: 'datanest_alert',
    // datanest_system（app-system）
    sys_permission: 'datanest_system',
    sys_role: 'datanest_system',
    sys_role_permission: 'datanest_system',
    sys_user: 'datanest_system',
    sys_user_role: 'datanest_system',
};

/** 从 SQL 中识别业务表（from/into/update/join 后的标识符），路由到所属域库 */
export function resolveDb(sql: string): BizDb {
    // 先剥离单引号字符串字面量（'' 为转义），避免规则 SQL 表达式等字符串里的 FROM 表名被误识别
    const stripped = sql.replace(/'(?:[^']|'')*'/g, "''");
    const re = /(?:from|into|update|join)\s+(?:only\s+)?(?:public\.)?"?([a-zA-Z_][a-zA-Z0-9_]*)"?/gi;
    const dbs = new Set<BizDb>();
    let m: RegExpExecArray | null;
    while ((m = re.exec(stripped)) !== null) {
        const table = m[1].toLowerCase();
        const db = TABLE_DB[table];
        if (!db) {
            throw new Error(`[db] 未识别的业务表 ${table}，请在 TABLE_DB 补充映射。SQL: ${sql.slice(0, 150)}`);
        }
        dbs.add(db);
    }
    if (dbs.size === 0) {
        throw new Error(`[db] SQL 中未找到已知业务表，无法路由。SQL: ${sql.slice(0, 150)}`);
    }
    if (dbs.size > 1) {
        throw new Error(`[db] 跨库 SQL（拆库后不允许）：涉及 ${[...dbs].join(' + ')}。SQL: ${sql.slice(0, 150)}`);
    }
    return [...dbs][0];
}

/** 显式指定域库执行（跨库两步操作时使用） */
export function psqlDb(db: BizDb, sql: string): string {
    const out = execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out.trim();
}

/** 治理库（元数据/质量/血缘/采集/合规） */
export const psqlGov = (sql: string): string => psqlDb('datanest_governance', sql);
/** 工程库（数据源/同步任务/DAG） */
export const psqlEng = (sql: string): string => psqlDb('datanest_engineering', sql);
/** 系统库（用户/角色/权限） */
export const psqlSys = (sql: string): string => psqlDb('datanest_system', sql);
/** 告警库（规则/历史/DAG 告警） */
export const psqlAlert = (sql: string): string => psqlDb('datanest_alert', sql);

/** 自动按表路由执行 */
export function psql(sql: string): string {
    return psqlDb(resolveDb(sql), sql);
}

/** 单值查询，返回字符串或 null */
export function scalar(sql: string): string | null {
    const r = psql(sql);
    return r === '' || r === 'NULL' ? null : r;
}

/** 行列表查询（psql -A unaligned 输出，字段以 | 分隔） */
export function rows(sql: string): string[][] {
    const r = psql(sql);
    if (r === '') return [];
    return r.split('\n').map((line) => line.split('|'));
}

/** 执行 Doris 上的 SQL（通过 middleware-mysql 容器内的 mysql 客户端连 Doris） */
export function doris(sql: string): string {
    const out = execSync(
        'docker exec datanest-middleware-mysql mysql -h 192.168.119.135 -P 9030 -u root -ppassword -e ' +
        JSON.stringify(sql),
        {encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out
        .split('\n')
        .filter((l) => !l.includes('Using a password on the command line'))
        .join('\n')
        .trim();
}
