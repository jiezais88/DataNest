import type {ExecutionStatus as AnyExecutionStatus, SyncMode, TaskTriggerType} from '../constants/task';

export type {SyncMode, TaskTriggerType as SyncTriggerType};

export type SyncScheduleStatus = 'NORMAL' | 'PAUSED';
// 状态字符串统一在 constants/task.ts 的 ExecutionStatusEnum 声明，这里只收窄出业务域子集
export type SyncExecutionStatus = Extract<AnyExecutionStatus, 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TERMINATED'>;
export type SyncHistoryStatus = Extract<AnyExecutionStatus, 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TERMINATED'>;
export type SyncLogLevel = 'INFO' | 'WARN' | 'ERROR';

export interface SyncFieldMapping {
    sourceColumn: string;
    targetColumn: string;
    targetType?: string;
}

/**
 * 多表同步的源表 → 目标表映射明细（Sprint 4）。
 * 注意：写入时 SyncJobCreateRequest.sourceTablesDetail 是 JSON 字符串；
 * 读取时 SyncJob.sourceTablesDetail 是对象数组（后端 DTO 已反序列化）。
 */
export interface SourceTableDetail {
    sourceTable: string;
    targetTable: string;
    fieldMapping?: SyncFieldMapping[];
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
    /** 多表同步明细（响应为对象数组；单表任务为 [] 或 undefined） */
    sourceTablesDetail?: SourceTableDetail[];
    /** Sprint 4 限流配置 */
    rateLimitEnabled?: boolean;
    readRateLimitMbps?: number;
    writeRateLimitRowsPerSecond?: number;
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
    createdByName?: string;
    updatedByName?: string;
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
    /** 多表同步明细：后端要求 JSON 字符串（与响应的对象数组形态不同，提交前 JSON.stringify） */
    sourceTablesDetail?: string;
    /** Sprint 4 限流配置 */
    rateLimitEnabled?: boolean;
    readRateLimitMbps?: number;
    writeRateLimitRowsPerSecond?: number;
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
