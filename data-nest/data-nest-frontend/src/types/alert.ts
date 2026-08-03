// Sprint 5：全局告警中心 / 告警规则类型
// 对齐后端 task-core AlertRuleDTO / AlertObjectOptionDTO / AlertHistory / UserOptionDTO
// 注意：后端 JacksonConfig 将 Long 全局序列化为字符串，所有 ID 用 string 类型

export type AlertObjectType = 'DAG' | 'SYNC_JOB' | 'COLLECT_TASK';

export type AlertTriggerType = 'FAILURE' | 'TIMEOUT' | 'SUCCESS';

/** 邮件发送状态 */
export type AlertSendStatus = 'SUCCESS' | 'FAILED';

export interface AlertRuleDTO {
    id?: string;
    /** DAG / SYNC_JOB / COLLECT_TASK */
    objectType: AlertObjectType;
    objectId: string;
    /** 冗余名称，服务端解析 */
    objectName?: string;
    /** FAILURE / TIMEOUT / SUCCESS */
    triggerConditions: AlertTriggerType[];
    /** 超时阈值（分钟），仅勾选 TIMEOUT 时必填 */
    timeoutMinutes?: number;
    enabled: boolean;
    /** 接收用户 ID 列表 */
    userIds: string[];
    createdByName?: string;
    updatedByName?: string;
    createdAt?: string;
    updatedAt?: string;
}

/** 告警对象下拉选项（新增规则时选择 DAG / 同步任务 / 采集任务） */
export interface AlertObjectOption {
    id: string;
    name: string;
}

/** 告警发送历史（含对象名联查与发送状态） */
export interface AlertHistory {
    id: string;
    alertRuleId?: string;
    objectType: AlertObjectType;
    objectId: string;
    /** FAILURE / TIMEOUT / SUCCESS */
    alertType: AlertTriggerType;
    /** 实际发送的邮箱列表，分号分隔 */
    recipients?: string;
    /** SUCCESS / FAILED */
    sendStatus?: AlertSendStatus;
    sentAt?: string;
    /** 联查对象名（非表字段） */
    objectName?: string;
}

/** 用户选择器选项（仅返回已填写邮箱的用户） */
export interface UserOption {
    id: string;
    username: string;
    email: string;
}
