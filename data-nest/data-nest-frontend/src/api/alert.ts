// Sprint 5：全局告警中心 API
// 对齐后端：
//  - system-service  AlertRuleController(/alert-rules) + AlertHistoryController(/alert-history) + UserSelectorController(/users)
//  - engineering    SyncJobAlertRuleController(/sync-jobs/{id}/alert-rule) + DagAlertRuleController(/dev/dags/{dagId}/alert-rule)
//  - governance     CollectTaskAlertRuleController(/collect-tasks/{id}/alert-rule)
// 统一 .then(r => r.data) 拆信封，调用方直接拿数据。
import request from './request';
import type {PageResult, Result} from '../types/common';
import type {
    AlertHistory,
    AlertObjectOption,
    AlertObjectType,
    AlertRuleDTO,
    AlertSendStatus,
    AlertTriggerType,
    UserOption,
} from '../types/alert';

// =================== 规则 CRUD（全局告警中心） ===================

export const getAlertRules = (params: {
    page: number;
    pageSize: number;
    objectType?: AlertObjectType;
    keyword?: string;
}) =>
    request.get<Result<PageResult<AlertRuleDTO>>>('/system/alert-rules', {params}).then(r => r.data);

export const createAlertRule = (data: AlertRuleDTO) =>
    request.post<Result<AlertRuleDTO>>('/system/alert-rules', data).then(r => r.data);

export const updateAlertRule = (id: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>(`/system/alert-rules/${id}`, data).then(r => r.data);

export const deleteAlertRule = (id: string) =>
    request.delete<Result<null>>(`/system/alert-rules/${id}`).then(r => r.data);

export const toggleAlertRule = (id: string, enabled: boolean) =>
    request.put<Result<null>>(`/system/alert-rules/${id}/toggle`, undefined, {params: {enabled}}).then(r => r.data);

export const getAlertRuleObjectOptions = (objectType: AlertObjectType) =>
    request.get<Result<AlertObjectOption[]>>('/system/alert-rules/object-options', {params: {objectType}}).then(r => r.data);

export const getAlertRuleUsers = (id: string) =>
    request.get<Result<string[]>>(`/system/alert-rules/${id}/users`).then(r => r.data);

export const setAlertRuleUsers = (id: string, userIds: string[]) =>
    request.put<Result<null>>(`/system/alert-rules/${id}/users`, userIds).then(r => r.data);

// =================== 告警历史 ===================

export const getAlertHistory = (params: {
    page: number;
    pageSize: number;
    objectType?: AlertObjectType;
    objectId?: string;
    alertType?: AlertTriggerType;
    sendStatus?: AlertSendStatus;
}) =>
    request.get<Result<PageResult<AlertHistory>>>('/system/alert-history', {params}).then(r => r.data);

// =================== 用户选择器 ===================

export const getUsersWithEmail = (keyword?: string) =>
    request.get<Result<UserOption[]>>('/system/users/with-email', {params: {keyword}}).then(r => r.data);

// =================== 业务模块快捷入口（同一数据源 alert_rule） ===================

export const getSyncJobAlertRule = (id: string) =>
    request.get<Result<AlertRuleDTO>>(`/engineering/sync-jobs/${id}/alert-rule`).then(r => r.data);

export const putSyncJobAlertRule = (id: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>(`/engineering/sync-jobs/${id}/alert-rule`, data).then(r => r.data);

export const getDagAlertRule = (dagId: string) =>
    request.get<Result<AlertRuleDTO>>(`/engineering/dev/dags/${dagId}/alert-rule`).then(r => r.data);

export const putDagAlertRule = (dagId: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>(`/engineering/dev/dags/${dagId}/alert-rule`, data).then(r => r.data);

export const getCollectTaskAlertRule = (id: string) =>
    request.get<Result<AlertRuleDTO>>(`/governance/collect-tasks/${id}/alert-rule`).then(r => r.data);

export const putCollectTaskAlertRule = (id: string, data: AlertRuleDTO) =>
    request.put<Result<AlertRuleDTO>>(`/governance/collect-tasks/${id}/alert-rule`, data).then(r => r.data);
