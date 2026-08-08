import {execSync} from 'child_process';

/**
 * Sprint 7 拆库版 DB 辅助：按域库直连 PostgreSQL（用于播种与断言）。
 * 通过 docker exec -i 传 stdin，避免 Windows 下引号转义问题。
 *
 * 拆库口径（2026-08-07 起）：
 * - datanest_governance：asset_classification / metadata_* / quality_* / lineage_record
 * - datanest_engineering：datasource_connection / sync_job / dag_*
 * - datanest_system：sys_user
 * 注意：sprint5/6 helpers/db.ts 已改为按表自动路由到这 4 个域库（2026-08-07 适配），
 * 本模块保留显式 psqlDb 风格，语义一致。
 */
export type BizDb = 'datanest_governance' | 'datanest_engineering' | 'datanest_system' | 'datanest_alert';

export function psqlDb(db: BizDb, sql: string): string {
    const out = execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out.trim();
}

/** 治理库（资产目录/元数据/质量/血缘都在这） */
export const psqlGov = (sql: string): string => psqlDb('datanest_governance', sql);
/** 工程库（数据源连接） */
export const psqlEng = (sql: string): string => psqlDb('datanest_engineering', sql);
/** 系统库（用户） */
export const psqlSys = (sql: string): string => psqlDb('datanest_system', sql);

/** 单值查询（治理库），返回字符串或 null */
export function scalarGov(sql: string): string | null {
    const r = psqlGov(sql);
    return r === '' || r === 'NULL' ? null : r;
}
