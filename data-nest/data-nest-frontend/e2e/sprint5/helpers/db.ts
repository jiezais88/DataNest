import {execSync} from 'child_process';

/**
 * 直接执行 PostgreSQL 业务库查询/写入（用于播种与断言）
 * 通过 docker exec -i 传 stdin，避免 Windows 下引号转义问题
 */
export function psql(sql: string): string {
    const out = execSync(
        'docker exec -i datanest-middleware-postgres psql -U datanest -d datanest -t -A',
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out.trim();
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
