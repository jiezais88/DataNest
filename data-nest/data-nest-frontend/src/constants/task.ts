export const SyncModeEnum = {
    FULL: 'FULL',
    INCREMENTAL: 'INCREMENTAL',
} as const;

export type SyncMode = typeof SyncModeEnum[keyof typeof SyncModeEnum];

export const TaskTriggerTypeEnum = {
    MANUAL: 'MANUAL',
    CRON: 'CRON',
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
} as const;

export type ExecutionStatus = typeof ExecutionStatusEnum[keyof typeof ExecutionStatusEnum];
