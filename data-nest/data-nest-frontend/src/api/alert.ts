// Sprint 6：全局告警中心 API（微服务化：收拢到 app-alert）
// 对齐后端：
//  - alert-service  AlertRuleController(/alert-rules) + AlertHistoryController(/alert-history)
//  - alert-service  AlertObjectRuleController(/rules/by-object，objectType=SYNC_JOB/DAG/COLLECT_TASK/QUALITY)
//  - system-service UserSelectorController(/users/with-email，保持不变)
// 统一 .then(r => r.data) 拆信封，调用方直接拿数据。
import request from './request';
import type {PageResult, Result} from '@/types/common';
import type {
    AlertHistory,
    AlertObjectOption,
    AlertObjectType,
    AlertRuleDTO,
    AlertSendStatus,
    AlertTriggerType,
    UserOption,
} from '@/types/alert';

// =================== 规则 CRUD（全局告警中心） ===================

export const getAlertRules = (params: {
    page: number;
    pageSize: number;
    objectType?: AlertObjectType;
    keyword?: string;
}) =>
    request.get<Result<PageResult<AlertRuleDTO>>>('/alert/alert-rules', {params}).then(r => r.data);

export const createAlertRule = (data: AlertRuleDTO) =>
    request.post<Result<AlertRuleDTO>>('/alert/alert-rules', data).then(r => r.data);

export const updateAlertRule = (id: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>(`/alert/alert-rules/${id}`, data).then(r => r.data);

export const deleteAlertRule = (id: string) =>
    request.delete<Result<null>>(`/alert/alert-rules/${id}`).then(r => r.data);

export const toggleAlertRule = (id: string, enabled: boolean) =>
    request.put<Result<null>>(`/alert/alert-rules/${id}/toggle`, undefined, {params: {enabled}}).then(r => r.data);

export const getAlertRuleObjectOptions = (objectType: AlertObjectType) =>
    request.get<Result<AlertObjectOption[]>>('/alert/alert-rules/object-options', {params: {objectType}}).then(r => r.data);

export const getAlertRuleUsers = (id: string) =>
    request.get<Result<string[]>>(`/alert/alert-rules/${id}/users`).then(r => r.data);

export const setAlertRuleUsers = (id: string, userIds: string[]) =>
    request.put<Result<null>>(`/alert/alert-rules/${id}/users`, userIds).then(r => r.data);

// =================== 告警历史 ===================

export const getAlertHistory = (params: {
    page: number;
    pageSize: number;
    objectType?: AlertObjectType;
    objectId?: string;
    alertType?: AlertTriggerType;
    sendStatus?: AlertSendStatus;
    sentAtFrom?: string;
    sentAtTo?: string;
}) =>
    request.get<Result<PageResult<AlertHistory>>>('/alert/alert-history', {params}).then(r => r.data);

export interface AlertHistoryStats {
    failure: number;
    timeout: number;
    lagExceeded: number;
    externalStop: number;
    success: number;
    sendFailed: number;
}

export const getAlertHistoryStats = (params: {
    objectType?: AlertObjectType;
    objectId?: string;
    sentAtFrom?: string;
    sentAtTo?: string;
}) =>
    request.get<Result<AlertHistoryStats>>('/alert/alert-history/stats', {params}).then(r => r.data);

// =================== 用户选择器（仍在 system 服务） ===================

export const getUsersWithEmail = (keyword?: string) =>
    request.get<Result<UserOption[]>>('/system/users/with-email', {params: {keyword}}).then(r => r.data);

// =================== 业务模块快捷入口（同一数据源 alert_rule，统一走 /alert/rules/by-object） ===================

export const getSyncJobAlertRule = (id: string) =>
    request.get<Result<AlertRuleDTO>>('/alert/rules/by-object', {params: {objectType: 'SYNC_JOB', objectId: id}}).then(r => r.data);

export const putSyncJobAlertRule = (id: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>('/alert/rules/by-object', data, {params: {objectType: 'SYNC_JOB', objectId: id}}).then(r => r.data);

export const getDagAlertRule = (dagId: string) =>
    request.get<Result<AlertRuleDTO>>('/alert/rules/by-object', {params: {objectType: 'DAG', objectId: dagId}}).then(r => r.data);

export const putDagAlertRule = (dagId: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>('/alert/rules/by-object', data, {params: {objectType: 'DAG', objectId: dagId}}).then(r => r.data);

export const getCollectTaskAlertRule = (id: string) =>
    request.get<Result<AlertRuleDTO>>('/alert/rules/by-object', {params: {objectType: 'COLLECT_TASK', objectId: id}}).then(r => r.data);

export const putCollectTaskAlertRule = (id: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>('/alert/rules/by-object', data, {params: {objectType: 'COLLECT_TASK', objectId: id}}).then(r => r.data);
