// Sprint 13 F1：自定义 SQL 前端纯函数助手（无 UI 依赖，可单测）。
// 词法级 :参数名 扫描（跳过字符串/注释）、只读预检、涉及表提取、预览 SQL 拼装。
// 说明：这里是「尽早反馈」的轻量前端预检；权威校验（只读 classify 9001/9002 / 参数一一对应 9018 /
// 敏感度+数据权限闸门 9019）由后端在保存时兜底执行，前端不替代。
import type {CustomSqlParamDef, CustomSqlParamType} from '@/types/data-service';

/** 自定义 SQL 表单状态（创建向导与编辑页共用） */
export interface CustomSqlState {
    datasourceId: string;
    sqlText: string;
    sqlParams: CustomSqlParamDef[];
    /** 涉及表限定名清单（db.table / db.schema.table / table，校验后刷新） */
    involvedTables: string[];
    /** 最近一次「校验 SQL」是否通过（未通过或未校验时禁止进入下一步） */
    validated: boolean;
    /** 校验结果消息（通过摘要 / 失败文案） */
    validateMessage: string | null;
    /** 校验后 SQL/参数是否被改动（编辑页提示重新校验） */
    dirty: boolean;
}

export const EMPTY_CUSTOM_SQL_STATE: CustomSqlState = {
    datasourceId: '',
    sqlText: '',
    sqlParams: [],
    involvedTables: [],
    validated: false,
    validateMessage: null,
    dirty: false,
};

/**
 * 词法扫描 SQL，对每个 :参数名 占位符调用 onParam(name, start, end)。
 * 跳过：单引号字符串（'' 转义）、双引号/反引号标识符、-- 行注释、/* *\/ 块注释。
 */
export function scanSqlPlaceholders(
    sql: string,
    onParam: (name: string, start: number, end: number) => void,
): void {
    const n = sql.length;
    let i = 0;
    while (i < n) {
        const ch = sql[i];
        const next = sql[i + 1];
        if (ch === '-' && next === '-') {
            while (i < n && sql[i] !== '\n') i++;
            continue;
        }
        if (ch === '/' && next === '*') {
            i += 2;
            while (i < n && !(sql[i] === '*' && sql[i + 1] === '/')) i++;
            i = Math.min(i + 2, n);
            continue;
        }
        if (ch === "'") {
            i++;
            while (i < n) {
                if (sql[i] === "'") {
                    if (sql[i + 1] === "'") {
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                i++;
            }
            continue;
        }
        if (ch === '"' || ch === '`') {
            i++;
            while (i < n && sql[i] !== ch) i++;
            i++;
            continue;
        }
        if (ch === ':' && /[A-Za-z_]/.test(next ?? '')) {
            let j = i + 1;
            while (j < n && /[A-Za-z0-9_]/.test(sql[j])) j++;
            onParam(sql.slice(i + 1, j), i, j);
            i = j;
            continue;
        }
        i++;
    }
}

/** 提取 SQL 中的 :参数名（去重保序） */
export function scanSqlParams(sql: string): string[] {
    const seen = new Set<string>();
    const out: string[] = [];
    scanSqlPlaceholders(sql, (name) => {
        if (!seen.has(name)) {
            seen.add(name);
            out.push(name);
        }
    });
    return out;
}

/** 涉及表提取：FROM/JOIN 后的限定名（db.table / db.schema.table / table），去重保序（展示用，权威解析在后端） */
export function extractInvolvedTables(sql: string): string[] {
    const withoutComments = sql
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/--.*$/gm, ' ');
    const re = /\b(?:FROM|JOIN)\s+([A-Za-z0-9_$.`"]+)/gi;
    const seen = new Set<string>();
    const out: string[] = [];
    let m: RegExpExecArray | null;
    while ((m = re.exec(withoutComments)) !== null) {
        const name = m[1].replace(/[`"]/g, '').replace(/\.+$/, '');
        if (name && !seen.has(name)) {
            seen.add(name);
            out.push(name);
        }
    }
    return out;
}

/** 只读预检：空 SQL / 多语句（分号后非空）/ 非 SELECT/WITH 开头（轻量，权威校验在后端） */
export function clientCheckReadOnly(sql: string): string | null {
    const trimmed = (sql ?? '').trim();
    if (!trimmed) return '请输入 SQL';
    const core = trimmed.replace(/;+\s*$/, '');
    if (core.includes(';')) {
        const rest = core.slice(core.lastIndexOf(';') + 1).trim();
        if (rest) return '仅支持一条查询语句（检测到「;」后仍有内容，多语句将被拒绝）';
    }
    const noComments = trimmed.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--.*$/gm, ' ').trim();
    const kw = (noComments.match(/^\s*([A-Za-z]+)/)?.[1] ?? '').toUpperCase();
    if (kw !== 'SELECT' && kw !== 'WITH') {
        return kw ? `仅支持只读查询（当前以 ${kw} 开头，修改/删除类语句将被拒绝）` : '无法识别 SQL 语句，请以查询语句开头';
    }
    return null;
}

/** 参数类型推断：按默认值字面量启发式推断（整数/小数/日期/日期时间/布尔，其余字符串） */
export function inferSqlParamType(value: string | null | undefined): CustomSqlParamType {
    const v = (value ?? '').trim();
    if (/^-?\d+$/.test(v)) return 'LONG';
    if (/^-?\d*\.\d+$/.test(v)) return 'DECIMAL';
    if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return 'DATE';
    if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?$/.test(v)) return 'DATETIME';
    if (/^(true|false)$/i.test(v)) return 'BOOLEAN';
    return 'STRING';
}

/** 试跑预览的示例值（参数未填默认值时按类型取示例，对齐 PRD CS-04「参数填默认值/示例值」） */
const SAMPLE_VALUES: Record<CustomSqlParamType, string> = {
    LONG: '1',
    DECIMAL: '1.0',
    DATE: '2026-01-01',
    DATETIME: '2026-01-01 00:00:00',
    STRING: '示例',
    BOOLEAN: 'true',
};

/**
 * 拼装预览 SQL：:参数名 → 默认值/示例值。
 * STRING/DATE/DATETIME 按字面量加单引号（单引号转义为 ''）；LONG/DECIMAL/BOOLEAN 裸值。
 * 仅供前端试跑预览，对外调用路径一律由后端 PreparedStatement 参数绑定，绝不拼串。
 */
export function buildPreviewSql(sql: string, params: CustomSqlParamDef[]): string {
    const defs = new Map(params.map((p) => [p.name, p]));
    return replaceSqlPlaceholders(sql, (name) => {
        const def = defs.get(name);
        const raw = (def?.defaultValue && def.defaultValue.trim()) || (SAMPLE_VALUES[def?.type ?? 'STRING'] ?? '');
        const type = def?.type ?? inferSqlParamType(raw);
        return (type === 'STRING' || type === 'DATE' || type === 'DATETIME')
            ? `'${raw.replace(/'/g, "''")}'`
            : raw || 'NULL';
    });
}

/** 统一替换 :参数名 为 resolve(name) 的结果（词法级，跳过字符串/注释） */
export function replaceSqlPlaceholders(sql: string, resolve: (name: string) => string): string {
    let out = '';
    let last = 0;
    scanSqlPlaceholders(sql, (_name, start, end) => {
        out += sql.slice(last, start);
        out += resolve(_name);
        last = end;
    });
    return out + sql.slice(last);
}

/** SQL 关键词（详情页高亮展示用） */
const SQL_KEYWORDS = new Set([
    'SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'CROSS', 'FULL',
    'ON', 'GROUP', 'BY', 'ORDER', 'HAVING', 'AND', 'OR', 'NOT', 'NULL', 'AS', 'LIMIT',
    'OFFSET', 'UNION', 'ALL', 'WITH', 'IN', 'BETWEEN', 'LIKE', 'IS', 'DESC', 'ASC',
    'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'DISTINCT', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
    'RECURSIVE', 'EXISTS', 'OVER', 'PARTITION',
]);

export type SqlTokenKind = 'kw' | 'str' | 'comment' | 'param' | 'plain';

export interface SqlToken {
    text: string;
    kind: SqlTokenKind;
}

const TOKEN_RE = /(\/\*[\s\S]*?\*\/|--[^\n]*|'(?:[^']|'')*'|`[^`]*`|"[^"]*"|:[A-Za-z_][A-Za-z0-9_]*|\b[A-Za-z_][A-Za-z0-9_]*\b)/g;

/** 轻量 SQL 高亮分词（详情页只读展示；非编辑器，不追求全覆盖语法） */
export function tokenizeSql(sql: string): SqlToken[] {
    const tokens: SqlToken[] = [];
    let last = 0;
    let m: RegExpExecArray | null;
    TOKEN_RE.lastIndex = 0;
    while ((m = TOKEN_RE.exec(sql)) !== null) {
        if (m.index > last) {
            tokens.push({text: sql.slice(last, m.index), kind: 'plain'});
        }
        const t = m[1];
        if (t.startsWith('/*') || t.startsWith('--')) {
            tokens.push({text: t, kind: 'comment'});
        } else if (t.startsWith("'") || t.startsWith('`') || t.startsWith('"')) {
            tokens.push({text: t, kind: 'str'});
        } else if (t.startsWith(':')) {
            tokens.push({text: t, kind: 'param'});
        } else if (SQL_KEYWORDS.has(t.toUpperCase())) {
            tokens.push({text: t, kind: 'kw'});
        } else {
            tokens.push({text: t, kind: 'plain'});
        }
        last = m.index + t.length;
    }
    if (last < sql.length) {
        tokens.push({text: sql.slice(last), kind: 'plain'});
    }
    return tokens;
}
