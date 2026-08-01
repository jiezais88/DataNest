import type {ExecutionStatus as AnyExecutionStatus, SyncMode, TaskTriggerType} from '../constants/task';

export type {SyncMode, TaskTriggerType as SyncTriggerType};

export type SyncScheduleStatus = 'NORMAL' | 'PAUSED';
// 状态字符串统一在 constants/task.ts 的 ExecutionStatusEnum 声明，这里只收窄出业务域子集
export type SyncExecutionStatus = Extract<AnyExecutionStatus, 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'>;
export type SyncHistoryStatus = Extract<AnyExecutionStatus, 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TERMINATED'>;
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
    triggerType: TaskTriggerType;
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
    xxlJobId?: number;
    description?: string;
    lastExecuteTime?: string;
    lastHistoryId?: string;
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
    triggerType: TaskTriggerType;
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
    triggerType?: TaskTriggerType | '';
    executionStatus?: SyncExecutionStatus | '';
    page: number;
    pageSize: number;
}

export interface SyncJobHistory {
    id: string;
    syncJobId: string;
    taskName?: string;
    /** 由 DAG 编排触发时的 dag_execution.id；手动/定时触发为 undefined */
    dagExecutionId?: string | number;
    /** DAG 编排触发时的 dag.id（用于跳转） */
    dagId?: string | number;
    /** DAG 编排触发时的 DAG 名称（用于展示） */
    dagName?: string;
    triggerType: TaskTriggerType;
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
    keyword?: string;
    startTimeFrom?: string;
    startTimeTo?: string;
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
