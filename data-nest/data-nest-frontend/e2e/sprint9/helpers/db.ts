import {execSync} from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/**
 * Sprint 9 E2E 外部依赖辅助：
 * - realtime/alert/sys 业务库直连（docker exec psql）
 * - test-mysql / test-postgres 源库造数
 * - MinIO（mc 容器内访问，savepoint 文件断言）
 * - Doris（湖仓行数断言，经 middleware-mysql 容器内 mysql client）
 * - MailHog（v2 API，邮件断言）
 * - Nacos（配置发布/查询，延迟阈值临时调低）
 */
export type BizDb = 'datanest_governance' | 'datanest_engineering' | 'datanest_system' | 'datanest_alert' | 'datanest_realtime';

export function psqlDb(db: BizDb, sql: string): string {
    const out = execSync(
        `docker exec -i datanest-middleware-postgres psql -U datanest -d ${db} -t -A`,
        {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    );
    return out.trim();
}

/** 实时库（cdc_*） */
export const psqlRt = (sql: string): string => psqlDb('datanest_realtime', sql);
/** 告警库（alert_*） */
export const psqlAlert = (sql: string): string => psqlDb('datanest_alert', sql);
/** 系统库（sys_user） */
export const psqlSys = (sql: string): string => psqlDb('datanest_system', sql);
/** 工程库 */
export const psqlEng = (sql: string): string => psqlDb('datanest_engineering', sql);

// ==================== 源库造数 ====================

function exec(sql: string, cmd: string): string {
    return execSync(cmd, {input: sql, encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024}).trim();
}

/** test-mysql（源库造数） */
export const mysqlT = (sql: string) =>
    exec(sql, 'docker exec -i datanest-middleware-test-mysql mysql -u root -proot123 testdb -N -B');

/** test-postgres（源库造数） */
export const pgT = (sql: string) =>
    exec(sql, 'docker exec -i datanest-middleware-test-postgres psql -U postgres -d postgres -t -A');

// ==================== Doris 湖仓 ====================

/** Doris 查询（经 middleware-mysql 容器内 mysql client；表不存在等错误返回 null） */
export function doris(sql: string): string | null {
    try {
        return exec(sql, 'docker exec -i datanest-middleware-mysql mysql -h192.168.119.135 -P9030 -uroot -ppassword -N -B');
    } catch {
        return null;
    }
}

/** 湖仓表行数（表不存在/查询失败返回 null） */
export function lakeCount(targetDb: string, table: string): number | null {
    const r = doris(`SELECT COUNT(*) FROM datalake_catalog.${targetDb}.${table}`);
    return r === null || r === '' || isNaN(Number(r)) ? null : Number(r);
}

// ==================== MinIO（savepoint 文件） ====================

/** MinIO 容器内 mc 命令封装（alias 每次现设，幂等） */
export function minio(args: string): string {
    return execSync(
        `docker exec datanest-middleware-minio sh -c "mc alias set mc http://localhost:9000 datanest datanest123 >/dev/null 2>&1; mc ${args}"`,
        {encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024},
    ).trim();
}

/** savepoint 文件是否存在（路径 s3a://datalake/savepoints/xxx → 桶 datalake，key savepoints/xxx） */
export function savepointExists(savepointPath: string | null | undefined): boolean {
    if (!savepointPath) return false;
    const key = savepointPath.replace(/^s3a:\/\//, '');
    const slash = key.indexOf('/');
    if (slash <= 0) return false;
    const bucket = key.slice(0, slash);
    const prefix = key.slice(slash + 1);
    try {
        // mc ls 必须带 alias 前缀（mc/<bucket>/<key>）
        const out = minio(`ls mc/${bucket}/${prefix} 2>&1`);
        return out.length > 0 && !out.includes('ERROR');
    } catch {
        return false;
    }
}

// ==================== MailHog ====================

export interface MailhogMessage {
    ID: string;
    To: {Mailbox: string; Domain: string}[];
    Content: {Headers: Record<string, string[]>; Body: string};
    Created: string;
}

function decodeMimeEncoded(s: string): string {
    if (!s || !s.includes('=?')) return s;
    return s.replace(/=\?([^?]+)\?([BQbq])\?([^?]*)\?=/g, (_m, _charset, enc, body) => {
        try {
            if (enc.toUpperCase() === 'B') return Buffer.from(body, 'base64').toString('utf8');
            return decodeURIComponent(body.replace(/=/g, '%'));
        } catch {
            return body;
        }
    });
}

export function mailhogList(): MailhogMessage[] {
    const out = execSync('curl.exe -s http://localhost:8025/api/v2/messages', {encoding: 'utf-8'});
    const json = JSON.parse(out);
    return json.items ?? [];
}

/** 当前邮件总数 */
export function mailhogCount(): number {
    return mailhogList().length;
}

/** 清空所有邮件（v1 与 v2 同源） */
export function mailhogDeleteAll(): void {
    execSync('curl.exe -s -X DELETE http://localhost:8025/api/v1/messages', {encoding: 'utf-8'});
}

/** 查找主题解码后包含关键词的邮件 */
export function mailhogFind(subjectKeyword: string): MailhogMessage[] {
    return mailhogList().filter(m => {
        const raw = m.Content.Headers?.Subject?.[0] ?? '';
        return decodeMimeEncoded(raw).includes(subjectKeyword);
    });
}

/** 邮件收件人列表 */
export function mailhogRecipients(m: MailhogMessage): string[] {
    return m.To.map(t => `${t.Mailbox}@${t.Domain}`);
}

// ==================== Nacos（配置发布，延迟阈值临时调低用） ====================

/** Nacos 登录拿 token（nacosGet/nacosPublish 共用） */
function nacosToken(): string {
    const login = execSync('curl.exe -s -X POST "http://localhost:8848/nacos/v1/auth/login" -d "username=nacos&password=nacos"',
        {encoding: 'utf-8'});
    return JSON.parse(login).accessToken;
}

/** 读取 Nacos 配置内容（Nacos 开启鉴权，必须带 token，否则返回 403 JSON） */
export function nacosGet(dataId: string, group = 'shared-configs'): string {
    const token = nacosToken();
    return execSync(
        `curl.exe -s "http://localhost:8848/nacos/v1/cs/configs?dataId=${dataId}&group=${group}&accessToken=${token}"`,
        {encoding: 'utf-8'}).trim();
}

/** 发布 Nacos 配置（先登录拿 token；整体覆盖） */
export function nacosPublish(dataId: string, content: string, group = 'shared-configs'): void {
    const token = nacosToken();
    // content 含多行 YAML，写临时文件 + curl --data-urlencode content@file，避免 shell 转义问题
    const tmpFile = path.join(os.tmpdir(), `nacos-pub-${Date.now()}.yaml`);
    fs.writeFileSync(tmpFile, content, 'utf-8');
    try {
        const out = execSync(
            `curl.exe -s -X POST "http://localhost:8848/nacos/v1/cs/configs?accessToken=${token}" ` +
            `--data-urlencode "dataId=${dataId}" --data-urlencode "group=${group}" --data-urlencode "content@${tmpFile}"`,
            {encoding: 'utf-8'});
        if (!out.trim().includes('true')) {
            throw new Error(`Nacos 发布配置失败: dataId=${dataId}, resp=${out}`);
        }
    } finally {
        fs.rmSync(tmpFile, {force: true});
    }
}

/** 替换 YAML 中的 lag.warn-threshold 值并发布（返回旧值，供测试还原） */
export function setLagThreshold(seconds: number, dataId = 'shared-realtime.yaml'): string {
    const old = nacosGet(dataId);
    const match = old.match(/warn-threshold:\s*(\d+)/);
    const oldVal = match ? match[1] : '30';
    const updated = old.replace(/warn-threshold:\s*\d+/, `warn-threshold: ${seconds}`);
    nacosPublish(dataId, updated);
    return oldVal;
}
