// Sprint 5：全局告警中心 / 告警规则类型
// 对齐后端 task-core AlertRuleDTO / AlertObjectOptionDTO / AlertHistory / UserOptionDTO
// 注意：后端 JacksonConfig 将 Long 全局序列化为字符串，所有 ID 用 string 类型

export type AlertObjectType = 'DAG' | 'SYNC_JOB' | 'COLLECT_TASK' | 'QUALITY';

export type AlertTriggerType = 'FAILURE' | 'TIMEOUT' | 'SUCCESS';

/** 邮件发送状态 */
export type AlertSendStatus = 'SUCCESS' | 'FAILED';

export interface AlertRuleDTO {
    id?: string;
    /** 规则名称（必填，同一对象类型下唯一） */
    name?: string;
    /** DAG / SYNC_JOB / COLLECT_TASK / QUALITY */
    objectType: AlertObjectType;
    /** 告警对象 ID 列表（多选） */
    objectIds: string[];
    /** 冗余名称，服务端解析；多对象时以「、」拼接 */
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
    /** 树形子节点（仅 DAG 类型使用：项目 → DAG） */
    children?: AlertObjectOption[];
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
    /** 告警规则名称（冗余/联查，规则删除后仍保留） */
    ruleName?: string;
    /** 质量批次告警聚合明细（每行一条「[等级] 规则名: 详情」），仅质量任务告警有值 */
    summary?: string;
    /** 关联的质量检查批次 ID（质量任务告警时回填） */
    qualityBatchId?: string;
}

/** 用户选择器选项（仅返回已填写邮箱的用户） */
export interface UserOption {
    id: string;
    username: string;
    email: string;
}
