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
