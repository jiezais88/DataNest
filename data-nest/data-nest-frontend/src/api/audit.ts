import request from './request';
import type {PageResult, Result} from '@/types/common';

/** 审计日志条目（对齐后端 system AuditLog 实体） */
export interface AuditLogItem {
    id: string;
    operatorId?: string | null;
    operatorName?: string | null;
    opType: string;
    resourceType: string;
    resourceId?: string | null;
    resourceName?: string | null;
    content?: string | null;
    result: string;
    errorMessage?: string | null;
    clientIp?: string | null;
    createdAt: string;
}

export interface AuditLogQuery {
    page: number;
    pageSize: number;
    operatorName?: string;
    opType?: string;
    resourceType?: string;
    startTime?: string;
    endTime?: string;
    keyword?: string;
}

/** 审计日志分页查询（仅超管） */
export const getAuditLogs = (params: AuditLogQuery) =>
    request.get<Result<PageResult<AuditLogItem>>>('/system/audit-logs', {params});

/** 审计日志详情（仅超管） */
export const getAuditLogDetail = (id: string) =>
    request.get<Result<AuditLogItem>>(`/system/audit-logs/${id}`);
