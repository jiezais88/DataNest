import {execSync} from 'child_process';

/**
 * Sprint 8 执行层：直接对测试数据源（middleware-test-mysql / middleware-test-postgres）执行 SQL，
 * 用于播种 e2e_s6_orders 目标表、清理数据与断言执行结果。
 *
 * 与 sprint6 helpers/db.ts（业务库 postgres psql）区分：这里连的是「被测数据源」本身。
 *
 * 注意：worker 容器内经 GenericSqlExecutor 通过容器名 middleware-test-mysql:3306 / middleware-test-postgres:5432
 * 直连；宿主映射端口为 3307 / 5433。本 helper 用 docker exec 进入容器内直连，避免宿主端口/防火墙差异。
 */

/** 对 middleware-test-mysql 执行 SQL（testuser / testpass123 / testdb） */
export function mysqlExec(sql: string): string {
    const out = execSync(
        'docker exec -i datanest-middleware-test-mysql mysql -u testuser -ptestpass123 testdb',
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out.trim();
}

/** 对 middleware-test-postgres 执行 SQL（testuser / testpass123 / testdb，schema public） */
export function pgExec(sql: string): string {
    const out = execSync(
        'docker exec -i datanest-middleware-test-postgres psql -U testuser -d testdb -t -A',
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out.trim();
}

/** mysql 单值查询（空结果返回 null） */
export function mysqlScalar(sql: string): string | null {
    const r = mysqlExec(sql);
    return r === '' || r === 'NULL' ? null : r;
}

/** postgres 单值查询（空结果返回 null） */
export function pgScalar(sql: string): string | null {
    const r = pgExec(sql);
    return r === '' || r === 'NULL' ? null : r;
}

/** 静默执行（忽略失败，用于幂等建表/清理） */
export function quiet(fn: (sql: string) => string, sql: string): void {
    try {
        fn(sql);
    } catch {
        /* 忽略：表可能不存在或已存在 */
    }
}

/**
 * 执行 Doris 上的 SQL（通过 middleware-mysql 容器内的 mysql 客户端连内置 Doris）。
 * 供同步任务自动触发的目标表建表/清理使用。
 */
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
