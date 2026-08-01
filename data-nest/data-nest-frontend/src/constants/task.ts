export const SyncModeEnum = {
    FULL: 'FULL',
    INCREMENTAL: 'INCREMENTAL',
} as const;

export type SyncMode = typeof SyncModeEnum[keyof typeof SyncModeEnum];

export const TaskTriggerTypeEnum = {
    MANUAL: 'MANUAL',
    CRON: 'CRON',
    DAG: 'DAG',
} as const;

export type TaskTriggerType = typeof TaskTriggerTypeEnum[keyof typeof TaskTriggerTypeEnum];

export const CollectModeEnum = {
    FULL: 'FULL',
    FULL_INCREMENT: 'FULL_INCREMENT',
} as const;

export type CollectMode = typeof CollectModeEnum[keyof typeof CollectModeEnum];

export const ReferenceTypeEnum = {
    COLLECT: 'COLLECT',
    SYNC: 'SYNC',
} as const;

export type ReferenceType = typeof ReferenceTypeEnum[keyof typeof ReferenceTypeEnum];

export const ExecutionStatusEnum = {
    SUCCESS: 'SUCCESS',
    FAILED: 'FAILED',
    NEVER_EXECUTED: 'NEVER_EXECUTED',
    RUNNING: 'RUNNING',
    PARTIAL: 'PARTIAL',
    PENDING: 'PENDING',
    TERMINATED: 'TERMINATED',
} as const;

/**
 * 执行状态母类型。各业务域的窄状态（types/collect.ts 的 TaskStatus/ExecutionStatus、
 * types/sync.ts 的 SyncExecutionStatus/SyncHistoryStatus）都用 Extract 从这里派生，
 * 状态字符串只在本文件声明一次。
 */
export type ExecutionStatus = typeof ExecutionStatusEnum[keyof typeof ExecutionStatusEnum];
