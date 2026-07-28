export type SyncMode = 'FULL' | 'INCREMENTAL';
export type SyncTriggerType = 'MANUAL' | 'CRON';
export type SyncScheduleStatus = 'NORMAL' | 'PAUSED';
export type SyncExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
export type SyncHistoryStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';
export type SyncLogLevel = 'INFO' | 'WARN' | 'ERROR';

export interface SyncFieldMapping {
    sourceColumn: string;
    targetColumn: string;
    targetType?: string;
}

export interface SyncJob {
    id: string;
    name: string;
    sourceDatasourceId: string;
    sourceDatasourceName?: string;
    sourceDatabase?: string;
    sourceSchema?: string;
    sourceTables: string[];
    syncMode: SyncMode;
    incrementalField?: string;
    triggerType: SyncTriggerType;
    cronExpression?: string;
    retryTimes: number;
    retryInterval: number;
    fieldMapping?: SyncFieldMapping[];
    targetDatabase?: string;
    targetTable?: string;
    status: SyncScheduleStatus;
    executionStatus: SyncExecutionStatus;
    scheduleEnabled: boolean;
    nextExecutionTime?: string;
    retryCount?: number;
    nextRetryAt?: string;
    xxlJobId?: number;
    description?: string;
    lastExecuteTime?: string;
    createdAt?: string;
    updatedAt?: string;
}

export interface SyncJobCreateRequest {
    name: string;
    sourceDatasourceId: string;
    sourceDatabase?: string;
    sourceSchema?: string;
    sourceTables: string[];
    syncMode: SyncMode;
    incrementalField?: string;
    triggerType: SyncTriggerType;
    cronExpression?: string;
    retryTimes: number;
    retryInterval: number;
    fieldMapping?: SyncFieldMapping[];
    targetDatabase?: string;
    targetTable?: string;
    description?: string;
    status?: SyncScheduleStatus;
    scheduleEnabled?: boolean;
}

export type SyncJobUpdateRequest = SyncJobCreateRequest;

export interface SyncJobQueryParams {
    keyword?: string;
    triggerType?: SyncTriggerType | '';
    executionStatus?: SyncExecutionStatus | '';
    page: number;
    pageSize: number;
}

export interface SyncJobHistory {
    id: string;
    syncJobId: string;
    triggerType: SyncTriggerType;
    status: SyncHistoryStatus;
    startTime?: string;
    endTime?: string;
    durationMs?: number;
    durationSeconds?: number;
    throughputRowsPerSecond?: number;
    sourceRows?: number;
    targetRows?: number;
    errorMessage?: string;
    sourceDatabase?: string;
    sourceSchema?: string;
    sourceTable?: string;
    targetDatabase?: string;
    targetTable?: string;
    syncMode?: SyncMode;
    incrementalField?: string;
    createdAt?: string;
}

export interface SyncJobHistoryQueryParams {
    syncJobId?: string;
    status?: SyncHistoryStatus | '';
    page: number;
    pageSize: number;
}

export interface SyncJobLog {
    id: string;
    historyId: string;
    syncJobId: string;
    level: SyncLogLevel;
    message: string;
    createdAt?: string;
}
