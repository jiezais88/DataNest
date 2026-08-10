import type {DagParameter} from '@/pages/engineering/dags/types';

/** 系统变量名（参数名不允许与它们重名） */
export const SYSTEM_VARIABLES = ['biz_date', 'current_time', 'dag_id'] as const;

/** 参数类型选项 */
export const PARAM_TYPE_OPTIONS = ['STRING', 'NUMBER', 'DATE', 'BOOLEAN'] as const;

// 参数名：字母/数字/下划线，3-30 位（PRD §6.4.1）
export const PARAM_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{2,29}$/;

/**
 * 校验 DAG 参数列表。
 * @returns 错误提示文本；校验通过返回 null
 */
export function validateDagParameters(params: DagParameter[]): string | null {
    const seen = new Set<string>();
    for (const p of params) {
        const name = p.paramName.trim();
        if (!name) return '参数名称不能为空';
        if (!PARAM_NAME_PATTERN.test(name)) return `参数名「${name}」不合法：字母/数字/下划线，3-30 位`;
        if ((SYSTEM_VARIABLES as readonly string[]).includes(name)) return `参数名「${name}」与系统变量重名`;
        if (seen.has(name)) return `参数名「${name}」重复`;
        seen.add(name);
        const value = (p.defaultValue ?? '').trim();
        if (!value) return `参数「${name}」必须填写默认值`;
        if (p.paramType === 'NUMBER' && Number.isNaN(Number(value))) return `参数「${name}」的默认值必须是数字`;
        if (p.paramType === 'DATE' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) return `参数「${name}」的默认值必须是 yyyy-MM-dd 格式`;
        if (p.paramType === 'BOOLEAN' && value !== 'true' && value !== 'false') return `参数「${name}」的默认值必须是 true 或 false`;
    }
    return null;
}
