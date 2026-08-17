// API 配置表单的值类型与提交归一助手（创建向导与编辑页共用）。
// 前端预校验与后端白名单同规则（尽早反馈，后端仍兜底）。
import type {ApiParamDef} from '@/types/data-service';

/** 表单值（提交时经 buildFilters/buildOrderBy/normalizePathInput 归一为后端请求） */
export interface ApiConfigValue {
    name: string;
    /** 用户输入的路径段（orders）或完整路径（/open-api/v1/orders），提交时归一 */
    path: string;
    /** 排序字段（'' = 无排序） */
    orderByField: string;
    orderByDir: 'ASC' | 'DESC';
    paginated: boolean;
    pageSizeMax: number;
    /** 暴露字段（进入 API 响应；空 = 全部字段） */
    exposedFields: string[];
    /** 参数化筛选：字段 -> 类型（'' = 不筛选） */
    filterTypes: Record<string, '' | 'EQ' | 'RANGE'>;
}

export interface ApiColumnRow {
    name: string;
    dataType?: string;
    comment?: string;
}

/** 从表名推导路径段（小写字母数字开头，可含 - _，对齐后端 PATH_SEGMENT_PATTERN） */
export function derivePathSegment(tableName: string): string {
    return tableName.toLowerCase().replace(/[^a-z0-9\-_]/g, '-').replace(/^[^a-z0-9]+/, '');
}

/** 归一用户输入为路径段（剥离前导 / 与 open-api/v1/ 前缀；非法字符不自动修，交给校验提示） */
export function normalizePathInput(raw: string): string {
    let p = raw.trim();
    if (p.startsWith('/')) p = p.substring(1);
    if (p.startsWith('open-api/v1/')) p = p.substring('open-api/v1/'.length);
    return p;
}

/** 前端预校验（与后端白名单同规则，尽早反馈；后端仍会兜底） */
export function validateApiConfig(value: ApiConfigValue, opts?: {customSql?: boolean}): string | null {
    if (!value.name.trim()) return '请填写 API 名称';
    if (value.name.trim().length > 100) return 'API 名称最长 100 字符';
    const segment = normalizePathInput(value.path);
    if (!/^[a-z0-9][a-z0-9\-_]{0,99}$/.test(segment)) {
        return 'API 路径非法：仅支持小写字母/数字开头，可含 - _，如 /open-api/v1/orders';
    }
    if (value.paginated && (!Number.isInteger(value.pageSizeMax) || value.pageSizeMax < 1 || value.pageSizeMax > 1000)) {
        return 'pageSize 上限需为 1~1000 的整数';
    }
    // 自定义 SQL 形态：返回列由 SQL 决定，不做字段白名单勾选（Sprint 13 PRD D7）
    if (!opts?.customSql && value.exposedFields.length === 0) return '请至少勾选 1 个暴露字段';
    return null;
}

/** 组装 filters（去重保序由后端兜底，这里直接映射） */
export function buildFilters(value: ApiConfigValue): ApiParamDef[] {
    return Object.entries(value.filterTypes)
        .filter(([, type]) => type !== '')
        .map(([field, type]) => ({field, type: type as 'EQ' | 'RANGE'}));
}

/** 组装 orderBy（'cnt DESC'；无排序返回 undefined） */
export function buildOrderBy(value: ApiConfigValue): string | undefined {
    return value.orderByField ? `${value.orderByField} ${value.orderByDir}` : undefined;
}
